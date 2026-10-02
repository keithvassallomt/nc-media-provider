package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

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
 * (PLAN 2.1) and never wait on the network; listing runs in [LibrarySyncWorker], because Android 17
 * cuts this app's network off once MediaProvider's call returns (PLAN 2.5a).
 */
/** The server refused the app password; the user has to sign in again (PLAN 4.4). */
class SignInRequiredException : Exception("The server refused this app's password")

class LibraryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val settings = LibrarySettings(appContext)
    private val client = NextcloudClient()
    private val diskCache = MediaDiskCache(appContext)
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
     * Records whether this app is selected (PLAN 4.6). Being deselected only counts as the user's
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

    /** The account for the preview player, unless signed out or refused (PLAN 5.4). */
    fun accountForPlayback(): NextcloudAccount? = if (credentials.signInRequired) null else credentials.load()

    fun callFactory(account: NextcloudAccount): okhttp3.Call.Factory = client.callFactory(account)

    /**
     * Where the preview plays [mediaId] from (PLAN 5.4): the phone's own copy when there is one and
     * the app may read it, otherwise the file on the server. Null for unknown media.
     */
    fun playbackUri(mediaId: String): Uri? {
        val item = store.media(mediaId) ?: return null
        item.mediaStoreUri?.takeIf { localMedia.hasAnyAccess() }?.let { return Uri.parse(it) }
        val account = accountForPlayback() ?: return null
        return client.fileUrl(account, item.href)?.let { Uri.parse(it.toString()) }
    }

    /** A page of photos for the keyboard, newest taken first (PLAN 4.8). Call off the main thread. */
    fun newestPhotos(after: MediaItem?, limit: Int): List<MediaItem> =
        if (hasAccount) store.newestPage(IMAGE_PREFIX, after, limit) else emptyList()

    /** What the diagnostics show (PLAN 4.6). Call off the main thread. */
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

    // Signing in (PLAN 4.1) and choosing folders (PLAN 4.3). Network calls: run them off the main thread.

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

    /** The folders directly inside [folder], without end-to-end encrypted ones (PLAN 4.3). */
    fun childFolders(folder: String): List<DavEntry> =
        client.listChildFolders(requireAccount(), folder).filterNot(DavEntry::isEncrypted).sortedBy { it.name.lowercase() }

    /**
     * A first selection for the folder picker: Memories' timeline folders when it is installed,
     * plus `/Photos` and `/InstantUpload` where they exist (PLAN 4.3).
     */
    fun suggestedFolders(): List<String> {
        val account = requireAccount()
        val memories = runCatching { client.memoriesTimelinePaths(account) }.getOrNull().orEmpty()
        val common = COMMON_PHOTO_FOLDERS.filter { folder -> runCatching { client.folderEntry(account, folder) }.isSuccess }
        return LibrarySettings.normalizeFolders(memories + common).filter { it != "/" }
    }

    /** Saves the folder choice; a different set starts a new library at the next sync (PLAN 2.7). */
    fun chooseFolders(folders: List<String>) {
        settings.folders = folders
        schedulePeriodicSync()
        requestSync(expedited = true)
    }

    /**
     * Signs out (PLAN 4.5): deletes the app password on the server, then everything on the phone.
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
     * Called on any 401 (PLAN 4.4). Stops all requests at once, since Nextcloud counts each refused
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
     * password as the token, and if so clears everything and confirms (PLAN 4.4).
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
     * forces a rebuild (PLAN 2.7). Anything else keeps it, so MediaProvider can sync incrementally.
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

    /** Asks for a sync as soon as the network allows. Requests made while one is queued are dropped. */
    fun requestSync(expedited: Boolean = false) {
        if (!isReady) return
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(NETWORK)
            .addTag(SYNC_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * The "Refresh now" button (PLAN 2.6): a full listing, queued after any sync already running. The
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

    /** Every queued, running and finished sync job, for showing progress (PLAN 2.5). */
    fun syncJobs(): Flow<List<WorkInfo>> = WorkManager.getInstance(appContext).getWorkInfosByTagFlow(SYNC_TAG)

    /** A sync every few hours, whatever the picker does (PLAN 2.6). */
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
     * thread, where the network is allowed (PLAN 2.5a). Usually a change check (PLAN 2.4); a full
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
                // A 503 is often maintenance mode, which status.php confirms (PLAN 4.4).
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
        val rematched = matchLocally()
        val durations = readVideoDurations(account, isStopped)
        store.pruneDeletions(DELETION_RETENTION_MS)
        store.markChecked()
        if (changed || rematched > 0 || durations) notifyPickerOfChanges()
        return changed
    }

    /**
     * Reads the durations of videos that have none yet from their headers (PLAN 5.3), newest first
     * and a batch per sync. Each video costs one or two 64 KiB Range requests, read four at a time:
     * a request costs Keith's server about 180 ms, and four small requests one after another took
     * 0.7 s a video. Stops as soon as the job is stopped: WorkManager can run a job inside the app's
     * own process, where Android 17 cut the network 3 s in (Phase 5). Returns true when any were stored.
     */
    private fun readVideoDurations(account: NextcloudAccount, isStopped: () -> Boolean): Boolean {
        val videos = store.videosWithoutDuration(DURATION_BATCH)
        if (videos.isEmpty()) return false
        val started = SystemClock.elapsedRealtime()
        val durations = ConcurrentHashMap<String, Long>()
        val stop = AtomicBoolean(false)
        val unauthorized = AtomicReference<NextcloudHttpException>()
        val pool = Executors.newFixedThreadPool(DURATION_PARALLEL)
        try {
            videos.map { video ->
                pool.submit {
                    if (stop.get() || isStopped() || SystemClock.elapsedRealtime() - started > DURATION_TIME_BUDGET_MS) return@submit
                    val reader = RangeReader(video.sizeBytes, DURATION_WINDOW_BYTES) { offset, length ->
                        client.fetchRange(account, video.href, video.etag, offset, length)
                    }
                    try {
                        val duration = VideoHeader.durationMillis(video.sizeBytes) { offset, length ->
                            ByteArray(length).also { reader.read(offset, it, 0, length) }
                        }
                        durations[video.id] = duration ?: DURATION_UNREADABLE
                    } catch (error: NextcloudHttpException) {
                        // A file that changed or vanished is picked up by the next listing.
                        if (error.statusCode == 401) {
                            unauthorized.set(error)
                            stop.set(true)
                        }
                    } catch (error: IOException) {
                        // The network went: keep what was read, and carry on at the next sync.
                        if (!stop.getAndSet(true)) Log.d(TAG, "Stopped reading video durations: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        unauthorized.get()?.let { throw it }
        val stored = store.applyDurations(durations)
        Log.i(
            TAG,
            "Read ${durations.count { it.value > 0 }} video durations (${durations.count { it.value < 0 }} unreadable) " +
                "of ${videos.size} in ${SystemClock.elapsedRealtime() - started} ms",
        )
        return stored
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
     * Brings every row's phone copy up to date (PLAN 3.2) and returns how many rows changed. Without
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
     * listed (PLAN 2.5). Folder etags are read before the listing, so anything that changes while it
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
     * Checks for changes without listing everything (PLAN 2.4). Nextcloud changes a folder's etag
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
     * reported gets an empty page and a new collection ID, which makes it rebuild (PLAN 2.3).
     */
    fun queryMedia(pageSize: Int, pageToken: String?, sinceGeneration: Long?): Page<MediaItem> {
        if (sinceGeneration != null && forcedRebuild(sinceGeneration)) return Page(emptyList(), null)
        return store.mediaPage(sinceGeneration, pageToken, pageSize)
    }

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
        // The phone's own copy needs no download (PLAN 3.3).
        item.mediaStoreUri?.let { uri ->
            runCatching { appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r", cancellationSignal) }
                .getOrNull()
                ?.let { return it }
        }
        if (item.isVideo) return openStreamed(item)
        val account = requireAuthorizedAccount()
        val file = withSlot(originalSlots, ORIGINAL_SLOT_WAIT_MS, "original") {
            diskCache.getOrDownload(MediaDiskCache.Area.ORIGINAL, key, cancellationSignal) { target ->
                unauthorizedStops { client.downloadFile(account, item.href, target, cancellationSignal) }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * A file handle whose reads become HTTP Range requests (PLAN 5.2), for videos: it is returned
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
     * file, as PLAN 1.4 specifies; whether the picker then rotates it twice is checked on a device
     * in 5.1.
     */
    @Throws(FileNotFoundException::class)
    fun openPreview(
        mediaId: String,
        requestedSize: Point,
        thumbnailOnly: Boolean,
        cancellationSignal: CancellationSignal?,
    ): AssetFileDescriptor {
        if (!thumbnailOnly) {
            return AssetFileDescriptor(openOriginal(mediaId, cancellationSignal), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val item = findItem(mediaId)
        val sizePx = if (maxOf(requestedSize.x, requestedSize.y) <= SMALL_PREVIEW_PX) SMALL_PREVIEW_PX else LARGE_PREVIEW_PX
        val key = "preview:${item.id}:${item.etag}:$sizePx"
        diskCache.peek(MediaDiskCache.Area.PREVIEW, key)?.let { return it.asAssetFileDescriptor() }
        val account = requireAuthorizedAccount()
        val file = withSlot(previewSlots, PREVIEW_SLOT_WAIT_MS, "preview") {
            previewGate.query {
                diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                    try {
                        unauthorizedStops { client.downloadPreview(account, item.id, sizePx, target, cancellationSignal) }
                    } catch (error: NextcloudHttpException) {
                        // The server has no preview for this file (HEIC or video without a server
                        // provider, say): a blank tile, not a failure.
                        if (error.statusCode == 404) throw FileNotFoundException("No server preview for ${item.id}")
                        throw error
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

    private fun findItem(mediaId: String): MediaItem =
        store.media(mediaId) ?: throw FileNotFoundException("Unknown media $mediaId")

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
     * moment before the streaming service has started (PLAN 5.2). HTTP errors aren't retried.
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

    /** The account, unless the server refused its password: then no request is even tried (PLAN 4.4). */
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
        private const val WIPE_CHECK_WORK = "remote-wipe-check"
        private const val SYNC_BACKOFF_MINUTES = 1L

        /** Where photos usually live, for the folder picker's first selection (PLAN 4.3). */
        private val COMMON_PHOTO_FOLDERS = listOf("/Photos", "/InstantUpload")
        private const val SYNC_TAG = "library-sync-job"
        private const val PERIODIC_SYNC_WORK = "library-sync-periodic"
        private const val PERIODIC_SYNC_HOURS = 6L
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private const val IMPORT_BATCH = 2_000

        /** Video durations read per sync; a WorkManager job may run for 10 minutes in all. */
        private const val DURATION_BATCH = 500
        private const val DURATION_TIME_BUDGET_MS = 3L * 60L * 1_000L
        private const val DURATION_PARALLEL = 4
        private const val DURATION_WINDOW_BYTES = 64 * 1024

        /** Stored for a video whose header couldn't be read, so it isn't tried at every sync. */
        private const val DURATION_UNREADABLE = -1L
        private const val LOCAL_MATCH_DELAY_MS = 5_000L
        private const val SELECTED_SEEN_RESOLUTION_MS = 5L * 60L * 1_000L

        /** Photos and videos (PLAN 5.3). Hidden files, such as a live photo's video half, are dropped. */
        private val MEDIA_PREFIXES = listOf("image/", "video/")

        /** The photo keyboard sends images only: Messenger takes no video from keyboards (PLAN 4.8). */
        private const val IMAGE_PREFIX = "image/"

        /**
         * What the listing includes. A change makes the next sync list everything, without
         * starting a new library: 2 added videos.
         */
        private const val LISTING_VERSION = 2

        /** Catches what etags miss: external storage, and metadata Nextcloud fills in later. */
        private const val FULL_LISTING_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val DELETION_RETENTION_MS = 180L * 24L * 60L * 60L * 1_000L

        private const val MEBIBYTE = 1024L * 1024L
        private const val STREAM_RETRIES = 3
        private const val STREAM_RETRY_DELAY_MS = 500L
        private const val SMALL_PREVIEW_PX = 256
        private const val LARGE_PREVIEW_PX = 1024
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
