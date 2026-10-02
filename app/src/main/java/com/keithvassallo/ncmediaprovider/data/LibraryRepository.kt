package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import com.keithvassallo.ncmediaprovider.local.LocalMediaIndex
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Whether a failed download means the server is unreachable, which arms the back-off that fails
 * the next few previews fast. One missing preview or a cancelled request says nothing about the
 * server, and treating them as failures blanked whole screens of thumbnails in Phase 1.5.
 */
internal fun isReachabilityFailure(error: Exception): Boolean = when (error) {
    is NextcloudHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
    is FileNotFoundException -> false
    is OperationCanceledException -> false
    is IOException -> error.message?.equals("Canceled", ignoreCase = true) != true
    else -> false
}

/**
 * Everything the picker reads goes through here. Picker calls are answered from the Room library
 * (PLAN 2.1) and never wait on the network; listing runs in [LibrarySyncWorker], because Android 17
 * cuts this app's network off once MediaProvider's call returns (PLAN 2.5a).
 */
class LibraryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val settings = LibrarySettings(appContext)
    private val client = NextcloudClient()
    private val diskCache = MediaDiskCache(appContext)
    private val localMedia = LocalMediaIndex(appContext)
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
    private val previewGate = RemoteQueryGate(PREVIEW_RETRY_DELAY_MS, ::isReachabilityFailure)

    /** Plain preferences only, never the Keystore: safe on the picker's 100 ms collection-info path. */
    val hasAccount: Boolean get() = credentials.account() != null

    val hasFullLocalMediaAccess: Boolean get() = localMedia.hasFullAccess()

    fun account(): CredentialStore.SavedAccount? = credentials.account()

    fun folder(): String = settings.folder

    /** Reads the database once; afterwards its state comes from memory. Call off the main thread. */
    fun warm() {
        if (hasAccount) store.state()
        warmLocalMediaIndex()
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

    fun accountName(): String = credentials.account()?.displayName ?: "Not set up"

    /** Asks for a sync as soon as the network allows. Requests made while one is queued are dropped. */
    fun requestSync(expedited: Boolean = false) {
        if (!hasAccount) return
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(NETWORK)
            .apply { if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(SYNC_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** A sync every few hours, whatever the picker does (PLAN 2.6). */
    fun schedulePeriodicSync() {
        if (!hasAccount) return
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(PERIODIC_SYNC_HOURS, TimeUnit.HOURS)
            .setConstraints(NETWORK)
            // Without a delay the first run fires at once, on top of the sync the provider asks for.
            .setInitialDelay(PERIODIC_SYNC_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(PERIODIC_SYNC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Brings the library up to date and returns true when anything changed. Runs on a WorkManager
     * thread, where the network is allowed (PLAN 2.5a). Usually a change check (PLAN 2.4); a full
     * listing on the first import, once a week, and whenever there are no folder etags to compare.
     */
    @Throws(IOException::class)
    fun syncNow(): Boolean = synchronized(syncLock) {
        val account = credentials.load() ?: return false
        val source = sourceKey() ?: return false
        if (store.state().sourceKey != source) {
            Log.i(TAG, "New server, user or folder set: starting a new library")
            store.resetFor(source)
        }
        val state = store.state()
        val due = System.currentTimeMillis() - state.lastFullListingMillis >= FULL_LISTING_INTERVAL_MS
        val changed = if (!state.imported || due || state.rootEtag.isEmpty()) {
            fullSync(account, settings.folder)
        } else {
            changeSync(account, settings.folder)
        }
        store.pruneDeletions(DELETION_RETENTION_MS)
        if (changed) notifyPickerOfChanges()
        return changed
    }

    /**
     * Lists everything and commits it as complete. The first import commits every [IMPORT_BATCH]
     * files and tells the picker each time, so photos appear long before a large library is fully
     * listed (PLAN 2.5). Folder etags are read before the listing, so anything that changes while it
     * runs still shows up at the next check.
     */
    private fun fullSync(account: NextcloudAccount, folder: String): Boolean {
        val started = System.currentTimeMillis()
        val etags = folderEtags(account, folder)
        val firstImport = !store.state().imported
        var changed = false
        val pending = ArrayList<MediaItem>()
        val listed = client.listFolder(account, folder, MIME_PREFIX) { batch ->
            if (!firstImport) return@listFolder
            pending += batch.toMediaItems()
            if (pending.size >= IMPORT_BATCH) {
                if (store.commit(pending, complete = false)) {
                    changed = true
                    notifyPickerOfChanges()
                }
                pending.clear()
            }
        }
        if (store.commit(listed.toMediaItems(), complete = true)) changed = true
        store.saveFolderEtags(etags.root.etag, etags.byKey)
        Log.i(
            TAG,
            "Full sync of ${listed.size} files in $folder in ${System.currentTimeMillis() - started} ms: " +
                "generation ${store.state().generation}, changed: $changed, first import: $firstImport",
        )
        return changed
    }

    /**
     * Checks for changes without listing everything (PLAN 2.4). Nextcloud changes a folder's etag
     * whenever anything below it changes, so an unchanged root etag means nothing to do; otherwise
     * one request returns every folder's etag, and only the folders whose etag moved are re-listed,
     * one level each. Favourites are checked apart, since favouriting changes no etag.
     */
    private fun changeSync(account: NextcloudAccount, folder: String): Boolean {
        val started = System.currentTimeMillis()
        val root = client.folderEntry(account, folder)
        var changed = store.applyFavorites(client.favoriteIds(account, folder, MIME_PREFIX))
        if (root.etag == store.state().rootEtag) {
            Log.d(TAG, "Change check: root etag unchanged, favourites changed: $changed")
            return changed
        }
        val etags = folderEtags(account, folder, root)
        val stored = store.folderEtags()
        val changedFolders = etags.byKey.filter { (key, etag) -> stored[key] != etag }.keys
        val removedFolders = stored.keys - etags.byKey.keys
        val listings = changedFolders.associateWith { key ->
            client.listDirectFiles(account, etags.hrefByKey.getValue(key), MIME_PREFIX).toMediaItems()
        }
        if (store.commitFolders(listings, removedFolders)) changed = true
        store.saveFolderEtags(root.etag, etags.byKey)
        Log.i(
            TAG,
            "Change check in ${System.currentTimeMillis() - started} ms: ${etags.byKey.size} folders, " +
                "${changedFolders.size} changed, ${removedFolders.size} removed: " +
                "generation ${store.state().generation}, changed: $changed",
        )
        return changed
    }

    /** The root's and every folder's etag, keyed by [folderKey]; the root counts as a folder. */
    private fun folderEtags(account: NextcloudAccount, folder: String, root: DavEntry = client.folderEntry(account, folder)): FolderEtags {
        val folders = client.listFolders(account, folder) + root
        return FolderEtags(
            root = root,
            byKey = folders.associate { folderKey(it.href) to it.etag },
            hrefByKey = folders.associate { folderKey(it.href) to it.href },
        )
    }

    private class FolderEtags(val root: DavEntry, val byKey: Map<String, String>, val hrefByKey: Map<String, String>)

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

    fun localUri(item: MediaItem): Uri? =
        localMedia.find(item.fileName, item.sizeBytes, item.dateTakenMillis, item.mimeType)

    fun invalidateLocalMedia() = localMedia.invalidate()

    fun warmLocalMediaIndex() = localMedia.warm()

    fun cacheStats(): CacheStats = diskCache.stats()

    @Throws(FileNotFoundException::class)
    fun openOriginal(mediaId: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        val item = findItem(mediaId)
        val key = "original:${item.id}:${item.etag}"
        diskCache.peek(MediaDiskCache.Area.ORIGINAL, key)?.let {
            return ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        localUri(item)?.let { uri ->
            runCatching { appContext.contentResolver.openFileDescriptor(uri, "r", cancellationSignal) }
                .getOrNull()
                ?.let { return it }
        }
        val account = requireAccount()
        val file = withSlot(originalSlots, ORIGINAL_SLOT_WAIT_MS, "original") {
            diskCache.getOrDownload(MediaDiskCache.Area.ORIGINAL, key, cancellationSignal) { target ->
                client.downloadFile(account, item.href, target, cancellationSignal)
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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
        val account = requireAccount()
        val file = withSlot(previewSlots, PREVIEW_SLOT_WAIT_MS, "preview") {
            previewGate.query {
                diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                    try {
                        client.downloadPreview(account, item.id, sizePx, target, cancellationSignal)
                    } catch (error: NextcloudHttpException) {
                        // The server has no preview for this file (HEIC or video without a server
                        // provider, say): a blank tile, not a failure.
                        if (error.statusCode == 404) throw FileNotFoundException("No server preview for ${item.id}")
                        throw error
                    } catch (error: IOException) {
                        // OkHttp reports a cancelled call as IOException("Canceled"). The picker
                        // cancels every tile scrolled off screen, so this must not look like a
                        // dead server (Phase 1.5).
                        if (cancellationSignal?.isCanceled == true) throw OperationCanceledException()
                        throw error
                    }
                }
            }
        }
        return file.asAssetFileDescriptor()
    }

    private fun findItem(mediaId: String): MediaItem =
        store.media(mediaId) ?: throw FileNotFoundException("Unknown media $mediaId")

    private fun List<RemoteFile>.toMediaItems(): List<MediaItem> =
        filter { !it.isHidden && it.sizeBytes > 0L && it.mimeType.startsWith(MIME_PREFIX) }.map(::toMediaItem)

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

    /** Which server, user and folder set this library holds (plain preferences only). */
    private fun sourceKey(): String? {
        val account = credentials.account() ?: return null
        return sha256(listOf(account.baseUrl, account.userId, settings.folder).joinToString("|"))
    }

    private fun requireAccount(): NextcloudAccount =
        credentials.load() ?: throw FileNotFoundException("No Nextcloud account is set up")

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
        private const val PERIODIC_SYNC_WORK = "library-sync-periodic"
        private const val PERIODIC_SYNC_HOURS = 6L
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private const val IMPORT_BATCH = 2_000

        /** Images only until video arrives in Phase 5. */
        private const val MIME_PREFIX = "image/"

        /** Catches what etags miss: external storage, and metadata Nextcloud fills in later. */
        private const val FULL_LISTING_INTERVAL_MS = 7L * 24L * 60L * 60L * 1_000L
        private const val DELETION_RETENTION_MS = 180L * 24L * 60L * 60L * 1_000L

        private const val SMALL_PREVIEW_PX = 256
        private const val LARGE_PREVIEW_PX = 1024
        private const val MAX_CONCURRENT_PREVIEW_DOWNLOADS = 4
        private const val MAX_CONCURRENT_ORIGINAL_DOWNLOADS = 2
        private const val PREVIEW_SLOT_WAIT_MS = 2_000L

        // Opening an original is an explicit pick, so it may wait for its turn.
        private const val ORIGINAL_SLOT_WAIT_MS = 60_000L

        // Short enough that a server coming back online recovers within one picker session.
        private const val PREVIEW_RETRY_DELAY_MS = 5_000L

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
            instance ?: LibraryRepository(context).also { instance = it }
        }
    }
}
