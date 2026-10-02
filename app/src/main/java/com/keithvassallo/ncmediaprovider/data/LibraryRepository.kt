package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.keithvassallo.ncmediaprovider.local.LocalMediaIndex
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Thrown instead of answering with an empty library, which MediaProvider would cache as real. */
class LibraryNotReadyException : IllegalStateException("The library has not been listed yet")

/**
 * Everything the picker reads goes through here. The proof of concept keeps one listing of one
 * folder in memory (PLAN 1.3 and 1.4); Phase 2 replaces it with a Room snapshot and change detection.
 */
class LibraryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val settings = LibrarySettings(appContext)
    private val client = NextcloudClient()
    private val diskCache = MediaDiskCache(appContext)
    private val localMedia = LocalMediaIndex(appContext)

    @Volatile
    private var snapshot: LibrarySnapshot? = null
    private val firstListing = CountDownLatch(1)
    private val loadLock = Any()
    private val loadInFlight = AtomicBoolean(false)
    private val loadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nc-library-load").apply { isDaemon = true }
    }

    @Volatile
    private var lastFailureMillis = 0L

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

    fun collectionId(): String {
        val account = credentials.account() ?: return "nc-unconfigured-v1"
        val identity = listOf(account.baseUrl, account.userId, settings.folder, settings.epoch, COLLECTION_FORMAT)
            .joinToString("|")
        return "nc-" + sha256(identity).take(24)
    }

    fun generation(): Long = snapshot?.generation ?: settings.generation

    fun itemCount(): Int? = snapshot?.items?.size

    fun accountName(): String = credentials.account()?.displayName ?: "Not set up"

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

    /**
     * Lists the library once per process. Returns true when the generation moved, which means the
     * picker must be told. Change detection while running arrives in Phase 2.
     */
    fun pollChanges(): Boolean = synchronized(loadLock) {
        if (snapshot != null || !hasAccount) return false
        if (System.currentTimeMillis() - lastFailureMillis < LOAD_RETRY_DELAY_MS) return false
        val account = credentials.load() ?: return false
        val folder = settings.folder
        val files = try {
            client.listFolder(account, folder, mimePrefix = "image/")
        } catch (error: Exception) {
            lastFailureMillis = System.currentTimeMillis()
            Log.w(TAG, "Listing $folder failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            return false
        }
        val items = files.asSequence()
            .filter { !it.isHidden && it.sizeBytes > 0L && it.mimeType.startsWith("image/") }
            .map(::toMediaItem)
            .sortedByDescending(MediaItem::dateTakenMillis)
            .toList()
        val fingerprint = sha256(folder + "\n" + items.map { "${it.id}:${it.etag}" }.sorted().joinToString("\n"))
        val changed = fingerprint != settings.fingerprint
        val generation = if (changed) settings.generation + 1 else settings.generation
        if (changed) settings.recordListing(generation, fingerprint)
        snapshot = LibrarySnapshot(generation, items)
        firstListing.countDown()
        Log.i(TAG, "Listed ${items.size} images in $folder at generation $generation (changed: $changed)")
        return changed
    }

    fun queryMedia(pageSize: Int, pageToken: String?, sinceGeneration: Long?): Page<MediaItem> =
        awaitSnapshot(QUERY_WAIT_MS).page(pageSize, pageToken, sinceGeneration)

    /** Deletions are tracked from Phase 2; a single listing per process has none to report. */
    @Suppress("UNUSED_PARAMETER")
    fun deletedSince(generation: Long): List<String> = emptyList()

    fun localUri(item: MediaItem): Uri? =
        localMedia.find(item.fileName, item.sizeBytes, item.dateTakenMillis, item.mimeType)

    fun invalidateLocalMedia() = localMedia.invalidate()

    fun warmLocalMediaIndex() = localMedia.warm()

    fun cacheStats(): CacheStats = diskCache.stats()

    @Throws(FileNotFoundException::class)
    fun openOriginal(mediaId: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor {
        val item = findItem(mediaId, ORIGINAL_SLOT_WAIT_MS)
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
        val item = findItem(mediaId, PREVIEW_SLOT_WAIT_MS)
        val sizePx = if (maxOf(requestedSize.x, requestedSize.y) <= SMALL_PREVIEW_PX) SMALL_PREVIEW_PX else LARGE_PREVIEW_PX
        val key = "preview:${item.id}:${item.etag}:$sizePx"
        diskCache.peek(MediaDiskCache.Area.PREVIEW, key)?.let { return it.asAssetFileDescriptor() }
        val account = requireAccount()
        val file = withSlot(previewSlots, PREVIEW_SLOT_WAIT_MS, "preview") {
            previewGate.query {
                diskCache.getOrDownload(MediaDiskCache.Area.PREVIEW, key, cancellationSignal) { target ->
                    client.downloadPreview(account, item.id, sizePx, target, cancellationSignal)
                }
            }
        }
        return file.asAssetFileDescriptor()
    }

    private fun findItem(mediaId: String, waitMillis: Long): MediaItem {
        val current = try {
            awaitSnapshot(waitMillis)
        } catch (error: LibraryNotReadyException) {
            throw FileNotFoundException("Library not listed yet").apply { initCause(error) }
        }
        return current.find(mediaId) ?: throw FileNotFoundException("Unknown media $mediaId")
    }

    /**
     * Picker calls can arrive in a fresh process before the first listing. Answering them as if the
     * library were empty would be cached by MediaProvider, so wait for the listing, then give up.
     */
    private fun awaitSnapshot(waitMillis: Long): LibrarySnapshot {
        snapshot?.let { return it }
        if (!hasAccount) return EMPTY
        if (loadInFlight.compareAndSet(false, true)) {
            loadExecutor.execute {
                try {
                    if (pollChanges()) notifyPickerOfChanges()
                } finally {
                    loadInFlight.set(false)
                }
            }
        }
        firstListing.await(waitMillis, TimeUnit.MILLISECONDS)
        return snapshot ?: throw LibraryNotReadyException()
    }

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
    )

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

    private fun isReachabilityFailure(error: Exception): Boolean = when (error) {
        // A 404 is one missing preview (HEIC without a server provider, say), not a dead server.
        is NextcloudHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
        is IOException -> true
        else -> false
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
        private const val COLLECTION_FORMAT = "v1"
        private val EMPTY = LibrarySnapshot(0L, emptyList())

        private const val LOAD_RETRY_DELAY_MS = 30_000L

        // MediaProvider syncs in the background, so a first listing of a large library may take a
        // while; previews are on screen and give up quickly.
        private const val QUERY_WAIT_MS = 60_000L

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
