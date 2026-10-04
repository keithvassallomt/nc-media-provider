package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.ImageDecoder
import android.graphics.Point
import android.net.ConnectivityManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.StatFs
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import com.keithvassallo.ncmediaprovider.local.LocalMatcher
import com.keithvassallo.ncmediaprovider.local.LocalMediaIndex
import com.keithvassallo.ncmediaprovider.ui.Notifications
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import org.json.JSONException

/**
 * Whether a failed download means the server is unreachable, which arms the back-off that fails
 * the next few previews fast. One missing preview or a cancelled request says nothing about the
 * server, and treating them as failures blanked whole screens of thumbnails in Phase 1.5.
 */
internal fun isReachabilityFailure(error: Exception): Boolean = when {
    error is NextcloudHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
    error is FileNotFoundException -> false
    error is OperationCanceledException -> false
    isCancellation(error) -> false
    else -> error is IOException
}

/**
 * OkHttp reports a cancelled call as IOException("Canceled"), and sometimes as a timeout whose
 * cause is that: the picker cancelled tiles while scrolling in Phase 5, and those "timeouts" armed
 * the back-off and blanked the screen.
 */
internal fun isCancellation(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(5).any { it is IOException && it.message.equals("Canceled", ignoreCase = true) }

/**
 * Everything the picker reads goes through here. Picker calls are answered from the Room library
 * (#12) and never wait on the network; listing runs in [LibrarySyncWorker], because Android 17
 * cuts this app's network off once MediaProvider's call returns (#16).
 */
/** The server refused the app password; the user has to sign in again (#29). */
class SignInRequiredException : Exception("The server refused this app's password")

class LibraryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val settings = LibrarySettings(appContext)
    private val client = NextcloudClient()

    /** A safety net over the pre-cache's budget; each run keeps the area to the budget, newest first. */
    @Volatile private var precacheAreaLimit = MediaDiskCache.Area.PRECACHE.maximumBytes
    private val diskCache = MediaDiskCache(appContext) { area ->
        when (area) {
            MediaDiskCache.Area.ORIGINAL -> settings.originalsCacheBytes
            MediaDiskCache.Area.PRECACHE -> precacheAreaLimit
            else -> area.maximumBytes
        }
    }
    private val localMatchExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nc-local-match").apply { isDaemon = true }
    }
    private var pendingLocalMatch: ScheduledFuture<*>? = null
    private val localMedia = LocalMediaIndex(appContext) { scheduleLocalMatch() }
    private val store by lazy { LibraryStore(LibraryDatabase.open(appContext)) }
    private val syncLock = Any()

    /**
     * The picker asks for a screenful of thumbnails at once and every request arrives on one of the
     * process' ~15 binder threads. Without a cap, a slow server parks all of them and the next
     * onGetMediaCollectionInfo has no thread left to run on, which stalls the app that opened the
     * picker.
     */
    private val previewSlots = Semaphore(MAX_CONCURRENT_PREVIEW_DOWNLOADS, true)
    private val originalSlots = Semaphore(MAX_CONCURRENT_ORIGINAL_DOWNLOADS, true)
    private val previewGate = RemoteQueryGate(PREVIEW_RETRY_DELAY_MS, ::isReachabilityFailure, failuresBeforeBackOff = PREVIEW_FAILURES_BEFORE_BACK_OFF)
    private val gridSizes = GridSizeTally()

    // Android reports no active network to an app whose network is blocked, as well as offline.
    private val reachability = ServerReachability(
        hasNetwork = { appContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null },
    )

    /** Plain preferences only, never the Keystore: safe on the picker's 100 ms collection-info path. */
    val hasAccount: Boolean get() = credentials.account() != null

    /** The server refused the app password; see [CredentialStore.signInRequired]. */
    val signInRequired: Boolean get() = credentials.signInRequired

    val foldersChosen: Boolean get() = settings.foldersChosen

    /** Signed in, folders chosen and the password still accepted: the only state that syncs. */
    val isReady: Boolean get() = hasAccount && settings.foldersChosen && !credentials.signInRequired

    val hasFullLocalMediaAccess: Boolean get() = localMedia.hasFullAccess()

    fun account(): CredentialStore.SavedAccount? = credentials.account()

    fun folders(): List<String> = settings.folders

    fun lastSyncError(): String? = settings.lastSyncError

    val wasSelectedProvider: Boolean get() = settings.wasSelectedProvider

    /** Whether MediaProvider has this app selected as the picker's cloud source right now. */
    fun isSelectedProvider(): Boolean = runCatching {
        MediaStore.isCurrentCloudMediaProviderAuthority(appContext.contentResolver, "${appContext.packageName}.cloudmedia")
    }.getOrDefault(false)

    /**
     * Records whether this app is selected (#31). Being deselected only counts as the user's
     * choice if the app hasn't been updated since it was last seen selected: an update deselects it
     * too, and a sync that runs straight after one must not erase what the reselect job
     * needs to know. Writes at most every few minutes, since every picker query calls this.
     */
    fun noteSelectedProvider(selected: Boolean) {
        val now = System.currentTimeMillis()
        if (selected) {
            if (!settings.wasSelectedProvider) settings.wasSelectedProvider = true
            if (now - settings.selectedSeenMillis > SELECTED_SEEN_RESOLUTION_MS) settings.selectedSeenMillis = now
        } else if (settings.wasSelectedProvider) {
            val updated = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            if (updated < settings.selectedSeenMillis) settings.wasSelectedProvider = false
        }
    }

    /** The account for the preview player, unless signed out or refused (#36). */
    fun accountForPlayback(): NextcloudAccount? = if (credentials.signInRequired) null else credentials.load()

    fun callFactory(account: NextcloudAccount): okhttp3.Call.Factory = client.callFactory(account)

    /**
     * Where the preview plays [mediaId] from (#36): the phone's own copy when there is one and
     * the app may read it, otherwise the file on the server. Null for unknown media.
     */
    fun playbackUri(mediaId: String): Uri? {
        val item = store.media(mediaId) ?: return null
        item.mediaStoreUri?.takeIf { localMedia.hasAnyAccess() }?.let { return Uri.parse(it) }
        val account = accountForPlayback() ?: return null
        return client.fileUrl(account, item.href)?.let { Uri.parse(it.toString()) }
    }

    // The photo keyboard (#60). Call these off the main thread.

    /**
     * A page of [source]'s photos older than the keyset position ([date], [id]), newest first; with
     * [newer], the next newer ones, newest first too.
     */
    fun keyboardPhotos(source: KeyboardSource, date: Long, id: String, newer: Boolean, limit: Int): List<MediaItem> {
        if (!hasAccount) return emptyList()
        val page = store.keyboardPage(source, IMAGE_PREFIX, date, id, newer, limit)
        return if (newer) page.asReversed() else page
    }

    /** The years [source] has photos from, newest first. */
    fun keyboardYears(source: KeyboardSource): List<Int> =
        if (hasAccount) store.keyboardYears(source, IMAGE_PREFIX, java.time.ZoneId.systemDefault()) else emptyList()

    fun keyboardAlbums(): List<PickerAlbum> = if (hasAccount) store.pickerAlbums() else emptyList()

    /** People with photos in the library, named first, as the newer picker shows them (#45). */
    fun keyboardPeople(): List<PickerPerson> = if (hasAccount) store.pickerPeople() else emptyList()

    // Thumbnail pre-cache (#59).

    val precacheEnabled: Boolean get() = settings.precacheEnabled

    val precacheMonths: Int get() = settings.precacheMonths

    /** The pre-cache's size when chosen by size; 0 when chosen by date ([precacheMonths]). */
    val precacheBytes: Long get() = settings.precacheBytes

    /** Ready and total thumbnails at the last run. */
    fun precacheReady(): Pair<Int, Int> = settings.precacheReady

    /** The pre-cache job, for showing its progress. */
    fun precacheJobs(): Flow<List<WorkInfo>> = WorkManager.getInstance(appContext).getWorkInfosForUniqueWorkFlow(PRECACHE_WORK)

    /**
     * Turns the pre-cache on or off, or changes its limit: [months] back (0 for everything), or
     * [bytes] when chosen by size. On, a run starts, which also trims what now falls outside; off,
     * its thumbnails go too, giving the space back (#48).
     */
    fun setPrecache(enabled: Boolean, months: Int, bytes: Long = 0L) {
        val wasEnabled = settings.precacheEnabled
        val changed = months != settings.precacheMonths || bytes != settings.precacheBytes
        settings.precacheEnabled = enabled
        settings.precacheMonths = months
        settings.precacheBytes = bytes
        if (enabled) {
            schedulePrecache(restart = changed)
        } else {
            WorkManager.getInstance(appContext).cancelUniqueWork(PRECACHE_WORK)
            if (wasEnabled) {
                settings.precacheReady = 0 to 0
                Thread { diskCache.clear(MediaDiskCache.Area.PRECACHE) }.start()
            }
        }
    }

    // Storage (#48).

    val originalsCacheBytes: Long get() = settings.originalsCacheBytes

    /** Sets how much space downloaded originals may take, and trims them to it at once. */
    fun setOriginalsCacheLimit(bytes: Long) {
        settings.originalsCacheBytes = bytes
        Thread { diskCache.prune(MediaDiskCache.Area.ORIGINAL) }.start()
    }

    /**
     * Deletes downloaded previews and originals, not the pre-cache, which has its own switch. Call
     * off the main thread.
     */
    fun clearDownloads() {
        diskCache.clear(MediaDiskCache.Area.PREVIEW)
        diskCache.clear(MediaDiskCache.Area.ORIGINAL)
    }

    /** What the Storage screen shows of the pre-cache's limit (#59). Off the main thread. */
    fun precachePlan(): PrecachePlan {
        val sizePx = settings.precacheSizePx
        val perItem = PreviewSizes.typicalBytes(sizePx)
        val months = if (hasAccount) store.monthCounts() else emptyList()
        // 12 months was a choice before the slider; someone who picked it still sees its size.
        val recent = listOf(1, 3, 6, 12).associateWith { if (hasAccount) store.countTakenSince(precacheCutoff(it)) else 0 }
        val free = freeBytes()
        val ceiling = PrecacheLimits.ceiling(free, diskCache.usedBytes(MediaDiskCache.Area.PRECACHE), months.sumOf(MonthCount::count) * perItem)
        return PrecachePlan(sizePx, perItem, months, recent, free, ceiling)
    }

    private fun freeBytes(): Long = runCatching { StatFs(appContext.cacheDir.path).availableBytes }.getOrDefault(0L)

    /** What the pre-cache may hold now: the size chosen, if any, within half the free space. */
    private fun precacheBudget(usedBytes: Long): Long {
        val half = PrecacheLimits.ceiling(freeBytes(), usedBytes, Long.MAX_VALUE)
        return settings.precacheBytes.takeIf { it > 0L }?.let { minOf(it, half) } ?: half
    }

    /**
     * Queues a pre-cache run on unmetered Wi-Fi with the battery not low, after any running one, or
     * in its place when [restart] (the limit changed).
     */
    fun schedulePrecache(restart: Boolean = false) {
        if (!isReady || !settings.precacheEnabled) return
        val request = OneTimeWorkRequestBuilder<ThumbnailPrecacheWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(PRECACHE_WORK, if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Keeps the pre-cache to its limit, newest first (#59): fetches the grid thumbnails inside
     * the date range and the space budget that aren't cached yet, four at a time, at the size this
     * phone's picker grid asks for, and deletes pre-cached ones outside it, so a smaller limit gives
     * the space back. Thumbnails the server can't make are marked so later runs skip them. Stops
     * when [isStopped] says so. Returns false only when the network failed, so the run is retried.
     */
    fun precacheThumbnails(isStopped: () -> Boolean, onProgress: (ready: Int, total: Int) -> Unit): Boolean {
        val account = credentials.load() ?: return true
        if (credentials.signInRequired || !settings.precacheEnabled) return true
        val sizePx = settings.precacheSizePx
        val cutoff = if (settings.precacheBytes > 0L) Long.MIN_VALUE else precacheCutoff(settings.precacheMonths)
        val budget = precacheBudget(diskCache.usedBytes(MediaDiskCache.Area.PRECACHE))
        precacheAreaLimit = budget + budget / 5
        val perItem = PreviewSizes.typicalBytes(sizePx)
        val total = minOf(store.countTakenSince(cutoff).toLong(), budget / perItem).toInt()
        val ready = AtomicInteger()
        val fetched = AtomicInteger()
        val failed = AtomicBoolean(false)
        val started = SystemClock.elapsedRealtime()
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT_PREVIEW_DOWNLOADS)
        var after: MediaItem? = null
        var kept = 0L
        var inside = true
        var trimmed = 0
        var outcome = "stopped"
        try {
            while (!isStopped() && !failed.get()) {
                val page = store.newestPage("", after, PRECACHE_PAGE)
                if (page.isEmpty()) {
                    if (inside) outcome = "done"
                    break
                }
                // Fetch only what can still fit, judged by a typical thumbnail; the walk below
                // counts what was really stored, and the next run fills any gap.
                val wanted = if (!inside) emptyList() else page.takeWhile { it.dateTakenMillis >= cutoff }.take(((budget - kept) / perItem).toInt() + 1)
                wanted.map { item -> pool.submit { precacheOne(account, item, sizePx, isStopped, failed, ready, fetched) } }.forEach { it.get() }
                if (failed.get() || isStopped()) break
                val wantedIds = wanted.mapTo(HashSet(), MediaItem::id)
                for (item in page) {
                    val key = previewKey(item, sizePx)
                    val size = diskCache.sizeOf(MediaDiskCache.Area.PRECACHE, key)
                    if (inside && item.id in wantedIds && kept + size <= budget) {
                        kept += size
                        continue
                    }
                    if (inside) {
                        inside = false
                        outcome = if (item.dateTakenMillis < cutoff) "done" else "full"
                    }
                    if (size > 0L) {
                        diskCache.remove(MediaDiskCache.Area.PRECACHE, key)
                        trimmed++
                    }
                }
                if (wanted.isNotEmpty()) onProgress(ready.get(), total)
                after = page.last()
                if (page.size < PRECACHE_PAGE) {
                    if (inside) outcome = "done"
                    break
                }
            }
        } finally {
            pool.shutdownNow()
        }
        if (failed.get()) outcome = "network failed"
        settings.precacheReady = ready.get() to if (outcome == "full") ready.get() else maxOf(total, ready.get())
        Log.i(
            TAG,
            "Pre-cache $outcome: ${fetched.get()} thumbnails at $sizePx px fetched, $trimmed outside the limit deleted, " +
                "${ready.get()} of $total ready, ${kept / MIB} of ${budget / MIB} MiB in ${SystemClock.elapsedRealtime() - started} ms",
        )
        return !failed.get()
    }

    /** One thumbnail for the pre-cache; a video the server can't preview gets a frame read on the phone. */
    private fun precacheOne(
        account: NextcloudAccount,
        item: MediaItem,
        sizePx: Int,
        isStopped: () -> Boolean,
        failed: AtomicBoolean,
        ready: AtomicInteger,
        fetched: AtomicInteger,
    ) {
        val key = previewKey(item, sizePx)
        if (diskCache.contains(MediaDiskCache.Area.PREVIEW, key) || diskCache.contains(MediaDiskCache.Area.PRECACHE, key)) {
            ready.incrementAndGet()
            return
        }
        if (isStopped() || failed.get() || diskCache.isMarkedMissing(MediaDiskCache.Area.PRECACHE, key)) return
        try {
            diskCache.getOrDownload(MediaDiskCache.Area.PRECACHE, key, null) { target ->
                client.downloadPreview(account, item.id, sizePx, target, null, viaPhotos = Albums.isAlbumPath(item.href))
            }
            ready.incrementAndGet()
            fetched.incrementAndGet()
        } catch (error: NextcloudHttpException) {
            when (error.statusCode) {
                // Videos get a frame read on the phone; images would mean downloading every HEIC
                // original, gigabytes for an iPhone library, so they are skipped.
                404 -> try {
                    diskCache.getOrDownload(MediaDiskCache.Area.PRECACHE, key, null) { target ->
                        thumbnailOnPhone(account, item, sizePx, target, null, imagesToo = false)
                    }
                    ready.incrementAndGet()
                    fetched.incrementAndGet()
                } catch (_: FileNotFoundException) {
                    diskCache.markMissing(MediaDiskCache.Area.PRECACHE, key)
                } catch (_: IOException) {
                    failed.set(true)
                }
                401 -> failed.set(true)
            }
        } catch (error: IOException) {
            failed.set(true)
        } catch (error: Exception) {
            Log.w(TAG, "Pre-cache skipped ${item.id}: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private fun precacheCutoff(months: Int): Long =
        if (months <= 0) Long.MIN_VALUE else System.currentTimeMillis() - months * 31L * 24L * 60L * 60L * 1_000L

    private fun previewKey(item: MediaItem, sizePx: Int) = "preview:${item.id}:${item.etag}:$sizePx"

    /** The thumbnail of the phone's own copy, made by MediaStore and cached; null if there is none. */
    private fun phoneCopyThumbnail(item: MediaItem, key: String, sizePx: Int, cancellationSignal: CancellationSignal?): File? {
        val uri = item.mediaStoreUri?.takeIf { localMedia.hasAnyAccess() } ?: return null
        return runCatching {
            diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                PhoneThumbnails.fromPhoneCopy(appContext.contentResolver, Uri.parse(uri), sizePx, target, cancellationSignal)
            }
        }.getOrNull()
    }

    /**
     * Makes a thumbnail on the phone for a file the server can't preview (#33, #35): a video
     * frame read through Range requests, or with [imagesToo] an image decoded from the downloaded
     * original (2 to 4 MB for a HEIC, kept in the originals cache). Decoding failures leave a blank
     * tile rather than counting against the server.
     */
    private fun thumbnailOnPhone(
        account: NextcloudAccount,
        item: MediaItem,
        sizePx: Int,
        target: File,
        cancellationSignal: CancellationSignal?,
        imagesToo: Boolean,
    ) {
        try {
            when {
                item.isVideo -> {
                    val reader = RangeReader(item.sizeBytes, FRAME_WINDOW_BYTES) { offset, length ->
                        client.fetchRange(account, item.href, item.etag, offset, length)
                    }
                    PhoneThumbnails.fromVideo(reader, item.durationMillis, sizePx, target)
                }
                imagesToo && item.mimeType.startsWith("image/") -> {
                    val original = diskCache.getOrDownload(MediaDiskCache.Area.ORIGINAL, "original:${item.id}:${item.etag}", cancellationSignal) { file ->
                        client.downloadFile(account, item.href, file, cancellationSignal)
                    }
                    PhoneThumbnails.fromImageFile(original, sizePx, target)
                }
                else -> throw FileNotFoundException("No server preview for ${item.id}")
            }
        } catch (error: NextcloudHttpException) {
            throw error
        } catch (error: FileNotFoundException) {
            throw error
        } catch (error: Exception) {
            if (cancellationSignal?.isCanceled == true || isCancellation(error)) throw OperationCanceledException()
            if (error is IOException && error !is ImageDecoder.DecodeException) throw error
            throw FileNotFoundException("Couldn't make a thumbnail for ${item.id}: ${error.javaClass.simpleName}").apply { initCause(error) }
        }
    }

    /**
     * The pre-cache follows the size this phone's picker grid asks for (#59): 256 px on Keith's
     * fold, 512 on the stock Pixel. The smaller thumbnails it held serve no tile any more, so they
     * go, and a run fetches the new size.
     */
    private fun followGridSize(sizePx: Int) {
        settings.precacheSizePx = sizePx
        Log.i(TAG, "The picker's grid asks for $sizePx px thumbnails; the pre-cache follows")
        if (!settings.precacheEnabled) return
        settings.precacheReady = 0 to 0
        Thread {
            runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(PRECACHE_WORK).result.get() }
            diskCache.clear(MediaDiskCache.Area.PRECACHE)
            schedulePrecache()
        }.start()
    }

    // The Memories layer (#39 to #41).

    val useMemories: Boolean get() = settings.useMemories

    /** Switches the Memories layer on or off; a sync queued after any running one applies it. */
    fun setUseMemories(enabled: Boolean) {
        settings.useMemories = enabled
        requestSync(afterRunning = true)
    }

    /** What the setup screen shows about Memories. Call off the main thread. */
    fun memoriesStatus(): MemoriesStatus {
        val (enriched, liveVideos) = if (hasAccount) store.memoriesStats() else 0 to 0
        return MemoriesStatus(settings.memoriesVersion, enriched, liveVideos)
    }

    /** [version] is what the last sync found: empty for no Memories, null before any check. */
    data class MemoriesStatus(val version: String?, val enriched: Int, val liveVideos: Int) {
        val supported: Boolean get() = !version.isNullOrEmpty() && MemoriesApi.isSupported(version)
    }

    /** What the diagnostics show (#31). Call off the main thread. */
    fun diagnostics(): Diagnostics {
        val state = if (hasAccount) store.state() else null
        return Diagnostics(
            items = if (hasAccount) store.mediaCount() else 0,
            generation = state?.generation ?: 0L,
            matched = if (hasAccount) store.matchedCount() else 0,
            lastCheckMillis = state?.lastCheckMillis ?: 0L,
            lastError = settings.lastSyncError,
            cache = diskCache.stats(),
        )
    }

    data class Diagnostics(
        val items: Int,
        val generation: Long,
        val matched: Int,
        val lastCheckMillis: Long,
        val lastError: String?,
        val cache: CacheStats,
    )

    // Signing in (#26) and choosing folders (#28). Network calls: run them off the main thread.

    fun serverStatus(baseUrl: String): ServerStatus = client.serverStatus(baseUrl)

    fun startLogin(baseUrl: String): LoginFlow = client.startLogin(baseUrl)

    fun pollLogin(baseUrl: String, flow: LoginFlow): LoginGrant? = client.pollLogin(baseUrl, flow)

    /**
     * Completes a sign-in: looks up the user ID (the login name may be an email address) and saves
     * the account. Signing in again as the same user keeps the library and folders; anyone else
     * starts over and chooses folders. Returns the saved account.
     */
    fun completeSignIn(typedUrl: String, grant: LoginGrant): NextcloudAccount {
        val baseUrl = ServerApi.chooseBaseUrl(typedUrl, grant.server)
        val provisional = NextcloudAccount(baseUrl, grant.loginName, grant.loginName, grant.appPassword)
        val account = provisional.copy(userId = client.userId(provisional))
        val previous = credentials.account()
        if (previous == null || previous.baseUrl != account.baseUrl || previous.userId != account.userId) settings.clearFolders()
        credentials.save(account)
        settings.lastSyncError = null
        Notifications.cancelSignInRequired(appContext)
        schedulePeriodicSync()
        requestSync(expedited = true)
        return account
    }

    /** The folders directly inside [folder], without end-to-end encrypted ones (#28). */
    fun childFolders(folder: String): List<DavEntry> =
        client.listChildFolders(requireAccount(), folder).filterNot(DavEntry::isEncrypted).sortedBy { it.name.lowercase() }

    /**
     * A first selection for the folder picker: Memories' timeline folders when it is installed,
     * plus `/Photos` and `/InstantUpload` where they exist (#28).
     */
    fun suggestedFolders(): List<String> {
        val account = requireAccount()
        val memories = runCatching { client.memoriesTimelinePaths(account) }.getOrNull().orEmpty()
        val common = COMMON_PHOTO_FOLDERS.filter { folder -> runCatching { client.folderEntry(account, folder) }.isSuccess }
        return LibrarySettings.normalizeFolders(memories + common).filter { it != "/" }
    }

    /** Saves the folder choice; a different set starts a new library at the next sync (#18). */
    fun chooseFolders(folders: List<String>) {
        settings.folders = folders
        schedulePeriodicSync()
        requestSync(expedited = true)
    }

    /**
     * Signs out (#30): deletes the app password on the server, then everything on the phone.
     * Returns false when the server couldn't be told; the phone is cleared anyway. Call off the
     * main thread.
     */
    fun signOut(): Boolean {
        val account = credentials.load()
        val revoked = account != null && !credentials.signInRequired &&
            runCatching { client.revokeAppPassword(account) }
                .onFailure { Log.w(TAG, "Couldn't revoke the app password: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
                .isSuccess
        clearLocalData()
        return revoked
    }

    /**
     * Called on any 401 (#29). Stops all requests at once, since Nextcloud counts each refused
     * one towards its brute-force throttling, then checks for a remote wipe in a job (the network
     * may be off on this thread) and asks the user to sign in again.
     */
    private fun onUnauthorized() {
        if (credentials.signInRequired) return
        Log.w(TAG, "The server refused the app password: no more requests until the user signs in again")
        credentials.signInRequired = true
        settings.lastSyncError = "The server refused this app's password"
        WorkManager.getInstance(appContext).run {
            cancelUniqueWork(SYNC_WORK)
            cancelUniqueWork(PERIODIC_SYNC_WORK)
            enqueueUniqueWork(
                WIPE_CHECK_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibrarySyncWorker>()
                    .setConstraints(NETWORK)
                    .setInputData(workDataOf(LibrarySyncWorker.KEY_WIPE_CHECK to true))
                    .build(),
            )
        }
        Notifications.showSignInRequired(appContext)
    }

    /**
     * Asks the server whether the user requested a wipe of this device, using the refused app
     * password as the token, and if so clears everything and confirms (#29).
     */
    @Throws(IOException::class)
    fun checkRemoteWipe() {
        val account = credentials.load() ?: return
        if (!client.wipeRequested(account.baseUrl, account.appPassword)) return
        Log.w(TAG, "The server asked to wipe this device")
        clearLocalData()
        runCatching { client.confirmWipe(account.baseUrl, account.appPassword) }
        Notifications.cancelSignInRequired(appContext)
        Notifications.showWiped(appContext)
    }

    /** Forgets the account, folders, library and caches; the picker sees an empty, new collection. */
    private fun clearLocalData() = synchronized(syncLock) {
        WorkManager.getInstance(appContext).run {
            cancelUniqueWork(SYNC_WORK)
            cancelUniqueWork(PERIODIC_SYNC_WORK)
        }
        credentials.clear()
        settings.clearFolders()
        settings.lastSyncError = null
        settings.memoriesVersion = null
        // A new instance ID gives a new collection ID, so MediaProvider drops what it had.
        store.resetFor("")
        diskCache.clear()
        notifyPickerOfChanges()
    }

    /** Reads the database once; afterwards its state comes from memory. Call off the main thread. */
    fun warm() {
        if (hasAccount) store.state()
    }

    /**
     * Changes when the server, user, folder set or database changes, or when [LibraryStore.bumpEpoch]
     * forces a rebuild (#18). Anything else keeps it, so MediaProvider can sync incrementally.
     */
    fun collectionId(): String {
        val source = sourceKey() ?: return "nc-unconfigured-v1"
        val state = store.state()
        return "nc-" + sha256(listOf(source, state.instanceId, state.epoch, COLLECTION_FORMAT).joinToString("|")).take(24)
    }

    fun generation(): Long = if (hasAccount) store.state().generation else 0L

    /** Call off the main thread. */
    fun itemCount(): Int = if (hasAccount) store.mediaCount() else 0

    /** "user@host" for the picker's cloud settings; null when signed out. */
    fun accountName(): String? = credentials.account()?.displayName

    /** When the last sync finished, or 0. Call off the main thread. */
    fun lastCheckMillis(): Long = if (hasAccount) store.state().lastCheckMillis else 0L

    /**
     * Asks for a sync as soon as the network allows. Requests made while one is queued are dropped,
     * unless [afterRunning] queues this one after it, for a change the running sync may have missed.
     */
    fun requestSync(expedited: Boolean = false, afterRunning: Boolean = false) {
        if (!isReady) return
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(NETWORK)
            .addTag(SYNC_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        val policy = if (afterRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(appContext).enqueueUniqueWork(SYNC_WORK, policy, request)
    }

    /**
     * The "Refresh now" button (#17): a full listing, queued after any sync already running. The
     * picker already runs a change check whenever it opens, so what a person pressing the button
     * needs is what change checks miss, such as files on external storage.
     */
    fun refreshNow() {
        if (!isReady) return
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(NETWORK)
            .addTag(SYNC_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(LibrarySyncWorker.KEY_FULL to true))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Every queued, running and finished sync job, for showing progress (#16). */
    fun syncJobs(): Flow<List<WorkInfo>> = WorkManager.getInstance(appContext).getWorkInfosByTagFlow(SYNC_TAG)

    /** A sync every few hours, whatever the picker does (#17). */
    fun schedulePeriodicSync() {
        if (!isReady) return
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(PERIODIC_SYNC_HOURS, TimeUnit.HOURS)
            .setConstraints(NETWORK)
            .addTag(SYNC_TAG)
            // Without a delay the first run fires at once, on top of the sync the provider asks for.
            .setInitialDelay(PERIODIC_SYNC_HOURS, TimeUnit.HOURS)
            .build()
        // UPDATE rather than KEEP, so a job scheduled by an older version picks up the tag.
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Brings the library up to date and returns true when anything changed. Runs on a WorkManager
     * thread, where the network is allowed (#16). Usually a change check (#15); a full
     * listing when [full] asks for one, on the first import, once a week, whenever there are no
     * folder etags to compare, and when the `.nomedia` setting changed.
     */
    @Throws(IOException::class, SignInRequiredException::class)
    fun syncNow(
        full: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
        isStopped: () -> Boolean = { false },
    ): Boolean = synchronized(syncLock) {
        if (!isReady) return false
        try {
            sync(full, onProgress, isStopped).also { settings.lastSyncError = null }
        } catch (error: NextcloudHttpException) {
            when {
                error.statusCode == 401 -> {
                    onUnauthorized()
                    throw SignInRequiredException()
                }
                // A 503 is often maintenance mode, which status.php confirms (#29).
                error.statusCode == 503 && runCatching { client.serverStatus(requireAccount().baseUrl).maintenance }.getOrDefault(false) ->
                    settings.lastSyncError = "The server is in maintenance mode"
                else -> settings.lastSyncError = "Server error ${error.statusCode}"
            }
            throw error
        } catch (error: IOException) {
            settings.lastSyncError = error.message ?: error.javaClass.simpleName
            throw error
        }
    }

    private fun sync(full: Boolean, onProgress: (SyncProgress) -> Unit, isStopped: () -> Boolean): Boolean {
        val account = credentials.load() ?: return false
        val source = sourceKey() ?: return false
        if (store.state().sourceKey != source) {
            Log.i(TAG, "New server, user or folder set: starting a new library")
            store.resetFor(source)
        }
        val folders = settings.folders
        val state = store.state()
        val due = System.currentTimeMillis() - state.lastFullListingMillis >= FULL_LISTING_INTERVAL_MS
        val needsFull = full || !state.imported || due || state.rootEtag.isEmpty() ||
            state.respectsNoMedia != settings.respectNoMedia || state.listingVersion != LISTING_VERSION
        val changed = if (needsFull) fullSync(account, folders, onProgress) else changeSync(account, folders, onProgress)
        // After the listing, which decides which album files the folders already hold.
        val albums = syncAlbums(account, full = needsFull) or store.applyAlbumRows()
        // Memories first: it fixes far more dates than the video headers do.
        val enriched = enrichFromMemories(account, fullRead = needsFull, isStopped)
        val people = syncPeople(account, full = needsFull, isStopped)
        val headers = if (isMeteredNetwork()) {
            // Up to two 64 KiB reads a video: over 100 MB for a first pass on a large library.
            Log.d(TAG, "Video headers wait for an unmetered network")
            false
        } else {
            readVideoHeaders(account, isStopped)
        }
        // After the dates settle: matching phone copies compares them.
        val rematched = matchLocally()
        store.pruneDeletions(DELETION_RETENTION_MS)
        store.markChecked()
        if (changed || albums || people || headers || enriched || rematched > 0) notifyPickerOfChanges()
        if (changed || enriched) schedulePrecache()
        return changed
    }

    private fun isMeteredNetwork(): Boolean =
        appContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    /**
     * Reads the duration and recording time of videos whose header hasn't been read yet (#35
     * and 6.0), newest first and a batch per sync. Each video costs one or two 64 KiB Range requests,
     * read four at a time: a request costs Keith's server about 180 ms, and four small requests one
     * after another took 0.7 s a video. Stops as soon as the job is stopped: WorkManager can run a
     * job inside the app's own process, where Android 17 cut the network 3 s in (Phase 5). Returns
     * true when any were stored.
     */
    private fun readVideoHeaders(account: NextcloudAccount, isStopped: () -> Boolean): Boolean {
        val videos = store.videosWithoutHeader(HEADER_BATCH)
        if (videos.isEmpty()) return false
        val started = SystemClock.elapsedRealtime()
        // ConcurrentHashMap takes no null values: an unreadable header is stored as a missing value.
        val headers = ConcurrentHashMap<String, Optional<VideoHeader.Info>>()
        val stop = AtomicBoolean(false)
        val unauthorized = AtomicReference<NextcloudHttpException>()
        val pool = Executors.newFixedThreadPool(HEADER_PARALLEL)
        try {
            videos.map { video ->
                pool.submit {
                    if (stop.get() || isStopped() || SystemClock.elapsedRealtime() - started > HEADER_TIME_BUDGET_MS) return@submit
                    val reader = RangeReader(video.sizeBytes, HEADER_WINDOW_BYTES) { offset, length ->
                        client.fetchRange(account, video.href, video.etag, offset, length)
                    }
                    try {
                        val header = VideoHeader.read(video.sizeBytes) { offset, length ->
                            ByteArray(length).also { reader.read(offset, it, 0, length) }
                        }
                        headers[video.id] = Optional.ofNullable(header)
                    } catch (error: NextcloudHttpException) {
                        // A file that changed or vanished is picked up by the next listing.
                        if (error.statusCode == 401) {
                            unauthorized.set(error)
                            stop.set(true)
                        }
                    } catch (error: IOException) {
                        // The network went: keep what was read, and carry on at the next sync.
                        if (!stop.getAndSet(true)) Log.d(TAG, "Stopped reading video headers: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        unauthorized.get()?.let { throw it }
        val read = headers.mapValues { it.value.orElse(null) }
        val stored = store.applyVideoHeaders(read)
        Log.i(
            TAG,
            "Read ${read.count { it.value != null }} video headers (${read.count { it.value == null }} unreadable, " +
                "${read.count { it.value?.recordedMillis != null }} with a recording time) of ${videos.size} " +
                "in ${SystemClock.elapsedRealtime() - started} ms",
        )
        return stored
    }

    /**
     * Brings the Nextcloud Photos albums up to date (#43): the album list at every sync, and an
     * album's files when Photos' count, cover or date range for it changed, or with a full listing.
     * Albums come on top of the library, so an error keeps what was stored (a refused password
     * still stops the sync), and a server without the Photos app has none. Returns true when
     * anything the picker shows changed.
     */
    private fun syncAlbums(account: NextcloudAccount, full: Boolean): Boolean {
        val started = SystemClock.elapsedRealtime()
        return try {
            val listed = client.listAlbums(account) ?: return store.clearAlbums()
            val stored = store.albums().associateBy(Album::id)
            val relisted = HashMap<String, List<AlbumItem>>()
            val albums = listed.map { entry ->
                val id = Albums.albumId(entry.href)
                if (full || stored[id]?.signature != entry.signature) {
                    relisted[id] = client.listDirectFiles(account, entry.href, MEDIA_PREFIXES)
                        .filter { !it.isHidden && it.sizeBytes > 0L }
                        .map { Albums.item(id, it) }
                }
                Album(id, entry.href, entry.name, entry.isShared, entry.lastPhotoId, entry.signature)
            }
            val changed = store.saveAlbums(albums, relisted)
            if (relisted.isNotEmpty() || changed) {
                Log.i(
                    TAG,
                    "Albums: ${albums.size} (${albums.count(Album::isShared)} shared), ${relisted.size} listed again " +
                        "in ${SystemClock.elapsedRealtime() - started} ms, changed: $changed",
                )
            }
            changed
        } catch (error: NextcloudHttpException) {
            if (error.statusCode == 401) throw error
            Log.w(TAG, "Couldn't list albums, keeping what was stored: HTTP ${error.statusCode}")
            false
        } catch (error: IOException) {
            Log.d(TAG, "Couldn't list albums, keeping what was stored: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            false
        }
    }

    /**
     * Reads the people Recognize found, through Memories (#45): the face groups at every sync,
     * and a person's photos when Memories' count or cover for them changed, or with a full listing.
     * People come on top of the library, like albums: an error keeps what was stored, and a server
     * without Memories and Recognize, or Memories switched off here, has none. Listing stops after
     * [PEOPLE_TIME_BUDGET_MS]; a person not reached waits for the next sync. Returns true when
     * anything changed.
     */
    private fun syncPeople(account: NextcloudAccount, full: Boolean, isStopped: () -> Boolean): Boolean {
        if (!settings.useMemories || settings.memoriesVersion?.let(MemoriesApi::isSupported) != true) {
            settings.peopleAvailable = false
            return store.clearPeople()
        }
        val started = SystemClock.elapsedRealtime()
        return try {
            val clusters = client.memoriesClusters(account)?.filter { it.count > 0 }
            if (clusters == null) {
                settings.peopleAvailable = false
                return store.clearPeople()
            }
            val stored = store.persons().associateBy(Person::id)
            val relisted = HashMap<String, List<String>>()
            var cutShort = false
            val people = clusters.map { cluster ->
                val id = People.personId(cluster)
                var signature = stored[id]?.signature.orEmpty()
                if (full || signature != cluster.signature) {
                    if (cutShort || isStopped() || SystemClock.elapsedRealtime() - started > PEOPLE_TIME_BUDGET_MS) {
                        cutShort = true
                    } else {
                        val days = client.memoriesPersonDays(account, cluster.memoriesKey)
                        relisted[id] = MemoriesApi.requestBatches(days.keys, days)
                            .flatMap { client.memoriesPersonFiles(account, cluster.memoriesKey, it) }
                            .map(MemoriesFile::id)
                            .distinct()
                        signature = cluster.signature
                    }
                }
                Person(id, cluster.memoriesKey, cluster.name, signature)
            }
            val changed = store.savePeople(people, relisted)
            val shown = store.pickerPeople()
            settings.peopleAvailable = shown.isNotEmpty()
            if (relisted.isNotEmpty()) {
                Log.i(
                    TAG,
                    "People: ${people.size} face groups (${people.count { it.name.isNotEmpty() }} named), ${relisted.size} listed again " +
                        "in ${SystemClock.elapsedRealtime() - started} ms${if (cutShort) ", the rest next time" else ""}; ${shown.size} with photos in the library",
                )
            }
            changed
        } catch (error: JSONException) {
            Log.w(TAG, "Memories answered the people request in a form this app can't read: ${error.message.orEmpty()}")
            settings.peopleAvailable = false
            store.clearPeople()
        } catch (error: NextcloudHttpException) {
            if (error.statusCode == 401) throw error
            Log.w(TAG, "Couldn't read people, keeping what was stored: HTTP ${error.statusCode}")
            false
        } catch (error: IOException) {
            Log.d(TAG, "Couldn't read people, keeping what was stored: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            false
        }
    }

    /**
     * Takes dates, sizes and live-photo pairs from Memories (#39 to #41). WebDAV still decides
     * what is in the library; Memories only overrides values of files it indexed, matched by file ID
     * and etag. Its API is internal and undocumented, so only tested versions are used, and nothing
     * here can fail a sync: a network or server error keeps the values last read, while Memories
     * switched off, gone, untested or answering in a form this can't read puts back core values.
     *
     * Reads the whole timeline with each full listing, otherwise only the days whose file count
     * changed. Reading stops when the job is stopped, on a network error, or after
     * [MEMORIES_TIME_BUDGET_MS], and what was read so far is stored: the next sync reads the rest.
     * Keith's 18,487 files took 20 requests: 3 s from a laptop, but 3 minutes from the phone in a
     * background job with its screen off. Returns true when any row changed.
     */
    private fun enrichFromMemories(account: NextcloudAccount, fullRead: Boolean, isStopped: () -> Boolean): Boolean {
        if (!settings.useMemories) return store.clearMemories()
        val started = SystemClock.elapsedRealtime()
        return try {
            val version = client.memoriesVersion(account)
            settings.memoriesVersion = version.orEmpty()
            if (version == null || !MemoriesApi.isSupported(version)) {
                if (version != null) Log.i(TAG, "Memories $version is untested: using core values")
                return store.clearMemories()
            }
            val days = client.memoriesDays(account)
            val stored = store.memoriesDays()
            val toRead = if (fullRead) days.keys else MemoriesApi.changedDays(days, stored, store.staleMemoriesDays())
            if (toRead.isEmpty() && stored.keys.all(days::containsKey)) return false
            val read = HashSet<Int>()
            val files = ArrayList<MemoriesFile>()
            var cutShort: String? = null
            for (batch in MemoriesApi.requestBatches(toRead, days)) {
                if (isStopped() || SystemClock.elapsedRealtime() - started > MEMORIES_TIME_BUDGET_MS) {
                    cutShort = "out of time"
                    break
                }
                try {
                    files += client.memoriesFiles(account, batch)
                    read += batch
                } catch (error: IOException) {
                    cutShort = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                    break
                }
            }
            val readMillis = SystemClock.elapsedRealtime() - started
            val changed = store.applyMemories(days, read, files)
            val (matched, liveVideos) = store.memoriesStats()
            Log.i(
                TAG,
                "Memories $version: read ${read.size} of ${toRead.size} days to read (${files.size} files" +
                    "${if (fullRead) ", full read" else ""}${cutShort?.let { ", stopped: $it" }.orEmpty()}) in $readMillis ms, " +
                    "stored in ${SystemClock.elapsedRealtime() - started - readMillis} ms; $matched rows use its values, " +
                    "$liveVideos live-photo videos hidden, changed: $changed",
            )
            changed
        } catch (error: JSONException) {
            Log.w(TAG, "Memories answered in a form this app can't read: using core values (${error.message.orEmpty()})")
            store.clearMemories()
        } catch (error: IOException) {
            Log.d(TAG, "Couldn't read Memories, keeping what was read before: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            false
        }
    }

    /**
     * Matches again once MediaStore has been quiet for [delayMillis]: taking one photo fires several
     * change notifications. Needs no network, so it runs on a plain thread.
     */
    fun scheduleLocalMatch(delayMillis: Long = LOCAL_MATCH_DELAY_MS) = synchronized(localMatchExecutor) {
        if (!hasAccount) return
        pendingLocalMatch?.cancel(false)
        pendingLocalMatch = localMatchExecutor.schedule(
            {
                runCatching { if (matchLocally() > 0) notifyPickerOfChanges() }
                    .onFailure { Log.w(TAG, "Matching phone copies failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}") }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    /** After the user grants or changes media access. */
    fun onLocalMediaAccessChanged() {
        localMedia.invalidate()
        scheduleLocalMatch(delayMillis = 0L)
    }

    /**
     * Brings every row's phone copy up to date (#23) and returns how many rows changed. Without
     * permission to read the phone's media, matches stay as they are: there is nothing to check
     * them against.
     */
    private fun matchLocally(): Int = synchronized(syncLock) {
        if (!hasAccount) return 0
        val started = SystemClock.elapsedRealtime()
        val local = localMedia.photos() ?: return 0
        val candidates = store.matchCandidates(local)
        val matches = LocalMatcher.match(candidates, local)
        val changed = store.applyLocalMatches(matches)
        Log.i(
            TAG,
            "Matched ${matches.size} rows (of ${candidates.size} candidates) to ${local.size} phone items in " +
                "${SystemClock.elapsedRealtime() - started} ms: $changed rows changed",
        )
        return changed
    }

    /**
     * Lists everything and commits it as complete. The first import commits every [IMPORT_BATCH]
     * files and tells the picker each time, so photos appear long before a large library is fully
     * listed (#16). Folder etags are read before the listing, so anything that changes while it
     * runs still shows up at the next check.
     */
    private fun fullSync(account: NextcloudAccount, folders: List<String>, onProgress: (SyncProgress) -> Unit): Boolean {
        val started = System.currentTimeMillis()
        onProgress(SyncProgress.Listing(0))
        val etags = folderEtags(account, folders)
        val hidden = hiddenFolders(account, folders)
        val firstImport = !store.state().imported
        var changed = false
        var seen = 0
        val pending = ArrayList<MediaItem>()
        val listing = client.listMedia(account, folders, MEDIA_PREFIXES) { batch ->
            if (batch.isEmpty()) return@listMedia
            seen += batch.size
            onProgress(SyncProgress.Listing(seen))
            if (!firstImport) return@listMedia
            pending += batch.toMediaItems(hidden)
            if (pending.size >= IMPORT_BATCH) {
                if (store.commit(pending, complete = false)) {
                    changed = true
                    // Matched before the picker hears of the batch, so phone copies never show twice.
                    matchLocally()
                    notifyPickerOfChanges()
                }
                pending.clear()
            }
        }
        if (store.commit(listing.files.toMediaItems(hidden), complete = true)) changed = true
        store.saveFolderState(
            etags.rootEtag, etags.byKey, hidden,
            respectsNoMedia = settings.respectNoMedia,
            duplicatePaths = listing.duplicates > 0,
            listingVersion = LISTING_VERSION,
        )
        Log.i(
            TAG,
            "Full sync of ${listing.files.size} files in ${folders.size} folders in ${System.currentTimeMillis() - started} ms: " +
                "${hidden.size} hidden folders, ${listing.duplicates} files at two paths, " +
                "generation ${store.state().generation}, changed: $changed, first import: $firstImport",
        )
        return changed
    }

    /**
     * Checks for changes without listing everything (#15). Nextcloud changes a folder's etag
     * whenever anything below it changes, so unchanged etags on the library folders mean nothing to
     * do; otherwise one request returns every folder's etag, and only the folders whose etag moved
     * are re-listed, one level each. Favourites are checked apart, since favouriting changes no etag.
     *
     * Falls back to a full listing in two rare cases a folder walk can't settle: a marker file such
     * as `.nomedia` came or went, which shows or hides whole trees; or folders were removed while some
     * file is reachable at two paths, since their files may still be reachable at the other path.
     */
    private fun changeSync(account: NextcloudAccount, folders: List<String>, onProgress: (SyncProgress) -> Unit): Boolean {
        val started = System.currentTimeMillis()
        onProgress(SyncProgress.Checking)
        val roots = folders.map { client.folderEntry(account, it) }
        val rootEtag = roots.joinToString(" ", transform = DavEntry::etag)
        val favoritesChanged = store.applyFavorites(client.favoriteIds(account, folders, MEDIA_PREFIXES))
        val state = store.state()
        if (rootEtag == state.rootEtag) {
            Log.d(TAG, "Change check: library folders unchanged, favourites changed: $favoritesChanged")
            return favoritesChanged
        }
        val hidden = hiddenFolders(account, folders)
        if (hidden != store.hiddenFolders()) {
            Log.i(TAG, "Hidden folders changed: listing everything")
            return fullSync(account, folders, onProgress) || favoritesChanged
        }
        val etags = folderEtags(account, folders, roots)
        val stored = store.folderEtags()
        val changedFolders = etags.byKey.filter { (key, etag) -> stored[key] != etag }.keys
        val removedFolders = stored.keys - etags.byKey.keys
        if (removedFolders.isNotEmpty() && state.duplicatePaths) {
            Log.i(TAG, "Folders removed while some files are at two paths: listing everything")
            return fullSync(account, folders, onProgress) || favoritesChanged
        }
        val listings = changedFolders.filterNot { isInsideAny(it, hidden) }.associateWith { key ->
            client.listDirectFiles(account, etags.hrefByKey.getValue(key), MEDIA_PREFIXES).toMediaItems(hidden)
        }
        val changed = store.commitFolders(listings, removedFolders) || favoritesChanged
        store.saveFolderState(rootEtag, etags.byKey, hidden)
        Log.i(
            TAG,
            "Change check in ${System.currentTimeMillis() - started} ms: ${etags.byKey.size} folders, " +
                "${changedFolders.size} changed, ${removedFolders.size} removed: " +
                "generation ${store.state().generation}, changed: $changed",
        )
        return changed
    }

    /** Every folder's etag, keyed by [folderKey]; the library folders count as folders. */
    private fun folderEtags(
        account: NextcloudAccount,
        folders: List<String>,
        roots: List<DavEntry> = folders.map { client.folderEntry(account, it) },
    ): FolderEtags {
        val entries = client.listSubfolders(account, folders) + roots
        return FolderEtags(
            rootEtag = roots.joinToString(" ", transform = DavEntry::etag),
            byKey = entries.associate { folderKey(it.href) to it.etag },
            hrefByKey = entries.associate { folderKey(it.href) to it.href },
        )
    }

    private class FolderEtags(val rootEtag: String, val byKey: Map<String, String>, val hrefByKey: Map<String, String>)

    /** Folders holding a marker such as `.nomedia`, by [folderKey]; none when the setting is off. */
    private fun hiddenFolders(account: NextcloudAccount, folders: List<String>): Set<String> =
        if (!settings.respectNoMedia) emptySet() else client.hidingMarkers(account, folders).mapTo(HashSet()) { parentFolderKey(it.href) }

    /**
     * Rows changed after [sinceGeneration]. A picker so far behind that pruned deletions can't be
     * reported gets an empty page and a new collection ID, which makes it rebuild (#14).
     */
    fun queryMedia(pageSize: Int, pageToken: String?, sinceGeneration: Long?): Page<MediaItem> {
        if (sinceGeneration != null && forcedRebuild(sinceGeneration)) return Page(emptyList(), null)
        return store.mediaPage(sinceGeneration, pageToken, pageSize)
    }

    /**
     * The albums the picker can show (#43), from the database. The older picker has no People
     * section, so there named people are albums too (#45).
     */
    fun queryAlbums(): List<PickerAlbum> {
        if (!hasAccount) return emptyList()
        val albums = store.pickerAlbums()
        if (PickerKind.isNewer(appContext)) return albums
        return albums + store.pickerPeople().filter { it.name.isNotEmpty() }
            .map { PickerAlbum(it.id, it.name, it.count, it.faceCoverId, it.newestMillis) }
    }

    /** A page of one album's or person's photos and videos; the token is the last file ID of the previous page. */
    fun queryAlbumMedia(albumId: String, pageSize: Int, pageToken: String?): Page<MediaItem> = when {
        !hasAccount -> Page(emptyList(), null)
        People.isPersonId(albumId) -> store.personPage(albumId, pageToken, pageSize)
        else -> store.albumPage(albumId, pageToken, pageSize)
    }

    // People for the newer picker's categories (#45).

    val peopleAvailable: Boolean get() = hasAccount && settings.peopleAvailable

    fun queryPeople(): List<PickerPerson> = if (hasAccount) store.pickerPeople() else emptyList()

    fun queryPersonMedia(personId: String, pageSize: Int, pageToken: String?): Page<MediaItem> =
        if (hasAccount) store.personPage(personId, pageToken, pageSize) else Page(emptyList(), null)

    fun deletedSince(sinceGeneration: Long, pageToken: String?, pageSize: Int): Page<String> {
        if (forcedRebuild(sinceGeneration)) return Page(emptyList(), null)
        return store.deletedPage(sinceGeneration, pageToken, pageSize)
    }

    private fun forcedRebuild(sinceGeneration: Long): Boolean {
        if (!store.isBehindDeletionFloor(sinceGeneration)) return false
        Log.w(TAG, "Picker at generation $sinceGeneration is behind the pruned deletions: forcing a rebuild")
        store.bumpEpoch()
        notifyPickerOfChanges()
        return true
    }

    /**
     * Tells MediaProvider that the library changed. A plain ContentResolver.notifyChange on the
     * provider's own URI is not observed by anyone, so MediaProvider would only find out when the
     * next picker opened, and then reset right in front of the user.
     */
    fun notifyPickerOfChanges() {
        runCatching {
            MediaStore.notifyCloudMediaChangedEvent(
                appContext.contentResolver,
                "${appContext.packageName}.cloudmedia",
                collectionId(),
            )
        }.onFailure {
            // Thrown when this app is not the selected cloud provider, which is fine.
            Log.d(TAG, "Cloud media change notification not delivered: ${it.javaClass.simpleName}")
        }
    }

    fun cacheStats(): CacheStats = diskCache.stats()

    @Throws(FileNotFoundException::class)
    fun openOriginal(mediaId: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        val item = findItem(mediaId)
        val key = "original:${item.id}:${item.etag}"
        diskCache.peek(MediaDiskCache.Area.ORIGINAL, key)?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        // The phone's own copy needs no download (#24).
        item.mediaStoreUri?.let { uri ->
            runCatching { appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r", cancellationSignal) }
                .getOrNull()
                ?.let { return it }
        }
        if (item.isVideo) return openStreamed(item)
        val account = requireAuthorizedAccount()
        // Before queueing for a download slot: offline, the answer is known at once (#47).
        reachability.check()
        val file = withSlot(originalSlots, ORIGINAL_SLOT_WAIT_MS, "original") {
            diskCache.getOrDownload(MediaDiskCache.Area.ORIGINAL, key, cancellationSignal) { target ->
                unauthorizedStops { reachability.request { client.downloadFile(account, item.href, target, cancellationSignal) } }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * A file handle whose reads become HTTP Range requests (#34), for videos: it is returned
     * at once, where downloading a large video first would outlast the picker's time limit. Each
     * stream gets its own thread for the reads and one for reading ahead.
     */
    @Throws(FileNotFoundException::class)
    private fun openStreamed(item: MediaItem): ParcelFileDescriptor {
        val account = requireAuthorizedAccount()
        val readThread = HandlerThread("nc-stream-${item.id}").apply { start() }
        val readAhead = Executors.newSingleThreadExecutor { Thread(it, "nc-stream-ahead-${item.id}").apply { isDaemon = true } }
        val reader = RangeReader(item.sizeBytes, prefetcher = readAhead) { offset, length ->
            withNetworkRetries { unauthorizedStops { client.fetchRange(account, item.href, item.etag, offset, length) } }
        }
        val opened = SystemClock.elapsedRealtime()
        val seconds = { (SystemClock.elapsedRealtime() - opened) / 1_000.0 }
        // The first chunk is read before the stream is handed over, so an unreachable server or a
        // changed file fails the open, where the picker can say so, instead of the app's first
        // read (#47). The chunk stays in the reader for that read.
        try {
            reachability.request { reader.read(0L, ByteArray(1), 0, 1) }
        } catch (error: Exception) {
            reader.close()
            readAhead.shutdownNow()
            readThread.quitSafely()
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Couldn't stream ${item.id}: ${error.message}").apply { initCause(error) }
        }
        Log.i(TAG, "Streaming ${item.id} (${item.sizeBytes / MEBIBYTE} MiB)")
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = item.sizeBytes

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                reader.read(offset, data, 0, size)
            } catch (error: Exception) {
                Log.w(TAG, "Stream ${item.id}: read at $offset failed after ${"%.1f".format(seconds())} s: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }

            override fun onRelease() {
                reader.close()
                readAhead.shutdownNow()
                readThread.quitSafely()
                StreamingService.streamClosed(appContext)
                Log.i(
                    TAG,
                    "Stream ${item.id} closed after ${"%.1f".format(seconds())} s: ${reader.requests} requests, " +
                        "${reader.bytesFetched / MEBIBYTE} MiB",
                )
            }
        }
        return try {
            appContext.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(readThread.looper))
                .also { StreamingService.streamOpened(appContext) }
        } catch (error: IOException) {
            readAhead.shutdownNow()
            readThread.quitSafely()
            throw FileNotFoundException("Couldn't stream ${item.id}: ${error.message}").apply { initCause(error) }
        }
    }

    /**
     * Thumbnails come from the server's preview endpoint. A full-size preview gets the original
     * file, as issue #9 specifies; whether the picker then rotates it twice is checked on a device
     * in 5.1. Only the picker's requests ([fromPicker]) teach the pre-cache its size (#59).
     */
    @Throws(FileNotFoundException::class)
    fun openPreview(
        mediaId: String,
        requestedSize: Point,
        thumbnailOnly: Boolean,
        cancellationSignal: CancellationSignal?,
        fromPicker: Boolean = true,
    ): AssetFileDescriptor {
        if (People.isFaceId(mediaId)) return openFace(mediaId, cancellationSignal)
        if (!thumbnailOnly) {
            return AssetFileDescriptor(openOriginal(mediaId, cancellationSignal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val item = findItem(mediaId)
        val requestedPx = maxOf(requestedSize.x, requestedSize.y)
        val sizePx = PreviewSizes.forTile(requestedPx)
        val precacheSizePx = settings.precacheSizePx
        if (fromPicker && PreviewSizes.isGridTile(requestedPx)) gridSizes.record(sizePx, precacheSizePx)?.let(::followGridSize)
        val key = previewKey(item, sizePx)
        diskCache.peek(MediaDiskCache.Area.PREVIEW, key)?.let { return it.asAssetFileDescriptor() }
        diskCache.peek(MediaDiskCache.Area.PRECACHE, key)?.let { return it.asAssetFileDescriptor() }
        // A larger pre-cached thumbnail serves a smaller tile: the picker scales it down.
        if (precacheSizePx > sizePx) {
            diskCache.peek(MediaDiskCache.Area.PRECACHE, previewKey(item, precacheSizePx))?.let { return it.asAssetFileDescriptor() }
        }
        phoneCopyThumbnail(item, key, sizePx, cancellationSignal)?.let { return it.asAssetFileDescriptor() }
        val account = requireAuthorizedAccount()
        reachability.check()
        val file = withSlot(previewSlots, PREVIEW_SLOT_WAIT_MS, "preview") {
            previewGate.query {
                diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                    try {
                        unauthorizedStops {
                            reachability.request {
                                // Core's preview endpoint can't see files shared only through an album.
                                client.downloadPreview(account, item.id, sizePx, target, cancellationSignal, viaPhotos = Albums.isAlbumPath(item.href))
                            }
                        }
                    } catch (error: NextcloudHttpException) {
                        // The server has no preview for this file (HEIC or video without a server
                        // provider, say): the phone makes one.
                        if (error.statusCode != 404) throw error
                        thumbnailOnPhone(account, item, sizePx, target, cancellationSignal, imagesToo = true)
                    } catch (error: IOException) {
                        // OkHttp reports a cancelled call as IOException("Canceled"). The picker
                        // cancels every tile scrolled off screen, so this must not look like a
                        // dead server (Phase 1.5).
                        if (cancellationSignal?.isCanceled == true || isCancellation(error)) throw OperationCanceledException()
                        throw error
                    }
                }
            }
        }
        return file.asAssetFileDescriptor()
    }

    /** A person's face as Memories crops it, for the picker's covers (#45); the same at every size. */
    private fun openFace(faceId: String, cancellationSignal: CancellationSignal?): AssetFileDescriptor {
        val person = store.person(People.personOfFace(faceId)) ?: throw FileNotFoundException("Unknown face $faceId")
        val key = "face:${person.id}:${person.signature}"
        diskCache.peek(MediaDiskCache.Area.PREVIEW, key)?.let { return it.asAssetFileDescriptor() }
        val account = requireAuthorizedAccount()
        reachability.check()
        val file = withSlot(previewSlots, PREVIEW_SLOT_WAIT_MS, "face") {
            diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                unauthorizedStops { reachability.request { client.downloadFacePreview(account, person.memoriesKey, target, cancellationSignal) } }
            }
        }
        return file.asAssetFileDescriptor()
    }

    /** A library row, or a file only an album holds (#43). */
    private fun findItem(mediaId: String): MediaItem =
        store.media(mediaId) ?: store.albumOnlyItem(mediaId) ?: throw FileNotFoundException("Unknown media $mediaId")

    /** Drops hidden files (the video half of a live photo, say), empty ones, and anything in [hiddenFolders]. */
    private fun List<RemoteFile>.toMediaItems(hiddenFolders: Set<String>): List<MediaItem> =
        filter { file -> !file.isHidden && file.sizeBytes > 0L && MEDIA_PREFIXES.any(file.mimeType::startsWith) }
            .map(::toMediaItem)
            .filterNot { isInsideAny(it.folder, hiddenFolders) }

    private fun toMediaItem(file: RemoteFile) = MediaItem(
        id = file.fileId,
        href = file.href,
        etag = file.etag,
        fileName = file.fileName,
        mimeType = file.mimeType,
        sizeBytes = file.sizeBytes,
        lastModifiedMillis = file.lastModifiedMillis,
        // Nextcloud's date taken when it has one, otherwise the modification time: the picker
        // drops rows without a date.
        dateTakenMillis = file.originalDateTimeMillis ?: file.lastModifiedMillis,
        width = file.width,
        height = file.height,
        isFavorite = file.isFavorite,
        folder = parentFolderKey(file.href),
    )

    /**
     * Which server, user and folder set this library holds (plain preferences only). One folder
     * gives the same key as before several were allowed, so upgrading keeps the library.
     */
    private fun sourceKey(): String? {
        val account = credentials.account() ?: return null
        return sha256(listOf(account.baseUrl, account.userId, settings.folders.joinToString("\n")).joinToString("|"))
    }

    private fun requireAccount(): NextcloudAccount =
        credentials.load() ?: throw FileNotFoundException("No Nextcloud account is set up")

    /**
     * Retries a stream's request after a network error, briefly: the network can be cut for a
     * moment before the streaming service has started (#34). HTTP errors aren't retried.
     */
    private fun <T> withNetworkRetries(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: IOException) {
                if (error is NextcloudHttpException || error is FileNotFoundException || ++attempt > STREAM_RETRIES) throw error
                Thread.sleep(STREAM_RETRY_DELAY_MS * attempt)
            }
        }
    }

    /** The account, unless the server refused its password: then no request is even tried (#29). */
    private fun requireAuthorizedAccount(): NextcloudAccount {
        if (credentials.signInRequired) throw FileNotFoundException("Sign in to Nextcloud again")
        return requireAccount()
    }

    /** Runs a download; a 401 stops everything (see [onUnauthorized]) and fails it as not found. */
    private fun <T> unauthorizedStops(block: () -> T): T = try {
        block()
    } catch (error: NextcloudHttpException) {
        if (error.statusCode != 401) throw error
        onUnauthorized()
        throw FileNotFoundException("Sign in to Nextcloud again")
    }

    /**
     * Runs [block] only if a download slot frees up quickly. Giving up leaves a blank tile that the
     * picker re-requests on the next scroll, which is far better than holding a binder thread.
     */
    private fun <T> withSlot(slots: Semaphore, waitMillis: Long, label: String, block: () -> T): T {
        if (!slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
            throw FileNotFoundException("Skipped $label download: too many in flight")
        }
        return try {
            block()
        } finally {
            slots.release()
        }
    }

    private fun File.asAssetFileDescriptor() = AssetFileDescriptor(
        ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY),
        0,
        length(),
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "LibraryRepository"

        /** Bump to make MediaProvider drop its cached copy of every user's library. */
        private const val COLLECTION_FORMAT = "v2"

        private const val SYNC_WORK = "library-sync"
        private const val PRECACHE_WORK = "thumbnail-precache"
        private const val PRECACHE_PAGE = 200
        private const val MIB = 1024L * 1024L
        private const val WIPE_CHECK_WORK = "remote-wipe-check"
        private const val SYNC_BACKOFF_MINUTES = 1L

        /** Where photos usually live, for the folder picker's first selection (#28). */
        private val COMMON_PHOTO_FOLDERS = listOf("/Photos", "/InstantUpload")
        private const val SYNC_TAG = "library-sync-job"
        private const val PERIODIC_SYNC_WORK = "library-sync-periodic"
        private const val PERIODIC_SYNC_HOURS = 6L
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private const val IMPORT_BATCH = 2_000

        /** Video headers read per sync; a WorkManager job may run for 10 minutes in all. */
        private const val HEADER_BATCH = 500
        private const val HEADER_TIME_BUDGET_MS = 3L * 60L * 1_000L
        private const val HEADER_PARALLEL = 4
        private const val HEADER_WINDOW_BYTES = 64 * 1024

        /** Memories reading per sync; the rest waits for the next one (#40). */
        private const val MEMORIES_TIME_BUDGET_MS = 2L * 60L * 1_000L

        /** Listing people's photos per sync, two or more requests each (#45). */
        private const val PEOPLE_TIME_BUDGET_MS = 2L * 60L * 1_000L

        private const val LOCAL_MATCH_DELAY_MS = 5_000L
        private const val SELECTED_SEEN_RESOLUTION_MS = 5L * 60L * 1_000L

        /** Photos and videos (#35). Hidden files, such as a live photo's video half, are dropped. */
        private val MEDIA_PREFIXES = listOf("image/", "video/")

        /** The photo keyboard sends images only: Messenger takes no video from keyboards (#60). */
        private const val IMAGE_PREFIX = "image/"

        /**
         * What the listing includes. A change makes the next sync list everything, without
         * starting a new library: 2 added videos, 3 pairs live photos by name.
         */
        private const val LISTING_VERSION = 3

        /** Catches what etags miss: external storage, and metadata Nextcloud fills in later. */
        private const val FULL_LISTING_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val DELETION_RETENTION_MS = 180L * 24L * 60L * 60L * 1_000L

        private const val MEBIBYTE = 1024L * 1024L

        /** Range window for reading a video's header and one keyframe. */
        private const val FRAME_WINDOW_BYTES = 512 * 1024
        private const val STREAM_RETRIES = 3
        private const val STREAM_RETRY_DELAY_MS = 500L
        private const val MAX_CONCURRENT_PREVIEW_DOWNLOADS = 4
        private const val MAX_CONCURRENT_ORIGINAL_DOWNLOADS = 2
        private const val PREVIEW_SLOT_WAIT_MS = 2_000L

        // Opening an original is an explicit pick, so it may wait for its turn.
        private const val ORIGINAL_SLOT_WAIT_MS = 60_000L

        // Short enough that a server coming back online recovers within one picker session.
        private const val PREVIEW_RETRY_DELAY_MS = 5_000L

        // The picker shows a refused thumbnail as a black tile until it is redrawn, so one odd
        // failure mustn't refuse a screenful: back off only when the server keeps failing.
        private const val PREVIEW_FAILURES_BEFORE_BACK_OFF = 3

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
            instance ?: LibraryRepository(context).also { instance = it }
        }
    }
}
