package com.keithvassallo.ncmediaprovider.provider

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract
import android.util.Log
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.data.Page
import com.keithvassallo.ncmediaprovider.data.RemoteQueryDeferredException
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("InlinedApi") // CloudMediaProvider constants are inlined and safe on the Android 14 minimum.
class NcCloudMediaProvider : CloudMediaProvider() {
    @Volatile
    private var advertisedCollection: CollectionVersion? = null

    private val repository: LibraryRepository
        get() = LibraryRepository.get(requireNotNull(context))

    override fun onCreate(): Boolean {
        val context = context ?: return false
        // The picker's first onQueryMedia lands within milliseconds of process start, and the
        // process is routinely killed between sessions. Without a head start the local-media
        // snapshot is never ready in time, so every row goes out without a MEDIA_STORE_URI and
        // MediaProvider caches it that way.
        SYNC_EXECUTOR.execute {
            runCatching {
                val repository = LibraryRepository.get(context)
                if (repository.hasAccount) {
                    // Opens the database off the binder threads, so the picker's 100 ms
                    // collection-info call never pays for it.
                    repository.warm()
                    repository.requestSync()
                }
            }
        }
        return true
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        enforceSystemCaller()
        val collection = currentCollection().also { advertisedCollection = it }
        val launchIntent = context?.packageManager?.getLaunchIntentForPackage(requireNotNull(context).packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = Bundle().apply {
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID,
                collection.id,
            )
            putLong(
                CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION,
                collection.generation,
            )
            putString(
                CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME,
                repository.accountName(),
            )
            if (launchIntent != null) {
                putParcelable(
                    CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_CONFIGURATION_INTENT,
                    launchIntent,
                )
            }
        }
        Log.d(TAG, "onGetMediaCollectionInfo -> ${collection.id} generation ${collection.generation}")
        // hasAccount reads plain preferences: this call has a 100 ms budget, so no Keystore here.
        if (repository.hasAccount) scheduleSyncCheck()
        return result
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        enforceSystemCaller()
        val albumId = extras.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID)
        val pageSize = extras.pageSize(DEFAULT_PAGE_SIZE)
        val pageToken = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
        // Present on incremental syncs. MediaProvider rejects a cursor that does not list it as
        // honored, and handles that by wiping the whole cloud catalog and rebuilding it.
        val sinceGeneration = extras.takeIf { it.containsKey(CloudMediaProviderContract.EXTRA_SYNC_GENERATION) }
            ?.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION)
        val collection = queryCollection()
        // No albums until Phase 7, so an album query is answered with an empty page.
        val page = if (albumId == null) {
            safelyQuery { repository.queryMedia(pageSize, pageToken, sinceGeneration) }
        } else {
            Page(emptyList(), null)
        }
        Log.d(TAG, "onQueryMedia since=$sinceGeneration token=$pageToken album=$albumId -> ${page.items.size} rows, next=${page.nextPageToken}")
        return mediaCursor(page).apply {
            this.extras = collectionExtras(collection.id, page.nextPageToken).apply {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf<String>().apply {
                        add(CloudMediaProviderContract.EXTRA_PAGE_SIZE)
                        add(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
                        if (albumId != null) add(CloudMediaProviderContract.EXTRA_ALBUM_ID)
                        if (sinceGeneration != null) add(CloudMediaProviderContract.EXTRA_SYNC_GENERATION)
                    },
                )
            }
        }
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        val previousGeneration = extras.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION, 0L)
        val pageSize = extras.pageSize(DEFAULT_PAGE_SIZE)
        // Android 17 may hand over the media pass's last token here; the store treats a token from
        // the other pass as "start from the beginning".
        val pageToken = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
        val page = runCatching {
            if (repository.hasAccount) repository.deletedSince(previousGeneration, pageToken, pageSize) else Page(emptyList(), null)
        }.onFailure(::logProviderFailure).getOrDefault(Page(emptyList(), null))
        Log.d(TAG, "onQueryDeletedMedia since=$previousGeneration token=$pageToken -> ${page.items.size} rows, next=${page.nextPageToken}")
        return MatrixCursor(arrayOf(CloudMediaProviderContract.MediaColumns.ID)).apply {
            page.items.forEach { addRow(arrayOf(it)) }
            this.extras = collectionExtras(collection.id, page.nextPageToken).apply {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf<String>().apply {
                        add(CloudMediaProviderContract.EXTRA_SYNC_GENERATION)
                        if (extras.containsKey(CloudMediaProviderContract.EXTRA_PAGE_SIZE)) add(CloudMediaProviderContract.EXTRA_PAGE_SIZE)
                        if (pageToken != null) add(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
                    },
                )
            }
        }
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        return MatrixCursor(ALBUM_PROJECTION).apply {
            this.extras = collectionExtras(collection.id)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): ParcelFileDescriptor {
        enforceSystemCaller()
        cancellationSignal?.throwIfCanceled()
        val started = System.nanoTime()
        return try {
            repository.openOriginal(mediaId, cancellationSignal).also {
                Log.i(TAG, "onOpenMedia $mediaId -> ${it.statSize} bytes in ${(System.nanoTime() - started) / 1_000_000} ms")
            }
        } catch (error: Exception) {
            Log.w(TAG, "onOpenMedia $mediaId failed: ${error.describe()}")
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Unable to open media").apply { initCause(error) }
        }
    }

    @Throws(FileNotFoundException::class)
    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        cancellationSignal: CancellationSignal?,
    ): AssetFileDescriptor {
        enforceSystemCaller()
        cancellationSignal?.throwIfCanceled()
        val thumbnailOnly = extras?.getBoolean(
            CloudMediaProviderContract.EXTRA_PREVIEW_THUMBNAIL,
            false,
        ) ?: false
        return try {
            repository.openPreview(mediaId, size, thumbnailOnly, cancellationSignal)
        } catch (error: Exception) {
            if (error is RemoteQueryDeferredException) {
                Log.d(TAG, "onOpenPreview $mediaId deferred: ${error.message}")
            } else {
                Log.w(TAG, "onOpenPreview $mediaId (thumbnail=$thumbnailOnly) failed: ${error.describe()}")
            }
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Unable to open preview").apply { initCause(error) }
        }
    }

    private fun mediaCursor(page: Page<MediaItem>): MatrixCursor =
        MatrixCursor(MEDIA_PROJECTION).apply {
            page.items.forEach { item ->
                val localUri = runCatching { repository.localUri(item) }.getOrNull()?.toString()
                PickerRowCheck.problem(item, localUri)?.let { problem ->
                    Log.w(TAG, "Dropped row ${item.id}: $problem")
                    return@forEach
                }
                addRow(
                    arrayOf<Any?>(
                        item.id,
                        item.dateTakenMillis,
                        // The generation this row last changed in, so an incremental sync only
                        // receives what changed (PLAN 2.3).
                        item.generation,
                        item.mimeType,
                        standardMimeExtension(item.mimeType),
                        item.sizeBytes,
                        localUri,
                        item.durationMillis.takeIf { it > 0L },
                        if (item.isFavorite) 1 else 0,
                        item.width.takeIf { it > 0 },
                        item.height.takeIf { it > 0 },
                        // Nextcloud previews arrive already rotated, with EXIF stripped, and its
                        // reported sizes are in display orientation (PLAN 0.2), so nothing to rotate.
                        0,
                    ),
                )
            }
        }

    private fun safelyQuery(block: () -> Page<MediaItem>): Page<MediaItem> {
        if (!repository.hasAccount) return Page(emptyList(), null)
        return runCatching(block).onFailure(::logProviderFailure).getOrDefault(Page(emptyList(), null))
    }

    private fun collectionExtras(collectionId: String, nextPageToken: String? = null): Bundle = Bundle().apply {
        putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, collectionId)
        if (nextPageToken != null) {
            putString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN, nextPageToken)
        }
    }

    private fun currentCollection() = CollectionVersion(repository.collectionId(), repository.generation())

    private fun queryCollection() = advertisedCollection ?: currentCollection()

    /**
     * Asks for a sync at most every [SYNC_CHECK_INTERVAL_MS]. The sync itself runs as a WorkManager
     * job, which keeps network access after this call returns (PLAN 2.5a), and tells MediaProvider
     * itself when the library changed.
     */
    private fun scheduleSyncCheck() {
        val now = System.currentTimeMillis()
        val last = LAST_SYNC_REQUEST.get()
        if (now - last < SYNC_CHECK_INTERVAL_MS || !LAST_SYNC_REQUEST.compareAndSet(last, now)) return
        runCatching { repository.requestSync(expedited = true) }.onFailure(::logProviderFailure)
    }

    private fun Bundle.pageSize(default: Int, maximum: Int = MAX_PAGE_SIZE): Int =
        getInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, default)
            .takeIf { it > 0 }
            ?.coerceAtMost(maximum)
            ?: default

    private fun standardMimeExtension(mimeType: String): Int = when (mimeType.lowercase()) {
        "image/gif" -> CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_GIF
        else -> CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION_NONE
    }

    private fun enforceSystemCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val permission = context?.checkCallingPermission(
            CloudMediaProviderContract.MANAGE_CLOUD_MEDIA_PROVIDERS_PERMISSION,
        ) ?: PackageManager.PERMISSION_DENIED
        if (permission != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Only Android's system photo picker may access this provider")
        }
    }

    /** Class and message of the error and its root cause, for logcat. */
    private fun Throwable.describe(): String {
        var root: Throwable = this
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val own = "${javaClass.simpleName}: ${message.orEmpty()}"
        return if (root === this) own else "$own (cause ${root.javaClass.simpleName}: ${root.message.orEmpty()})"
    }

    private fun logProviderFailure(error: Throwable) {
        if (error is RemoteQueryDeferredException) {
            Log.d(TAG, error.message.orEmpty())
        } else {
            Log.w(TAG, "Provider request failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    private data class CollectionVersion(val id: String, val generation: Long)

    private companion object {
        const val TAG = "NcCloudMediaProvider"
        // Cursors travel through a CursorWindow in shared memory, so wide pages are cheap.
        const val DEFAULT_PAGE_SIZE = 500
        const val MAX_PAGE_SIZE = 500

        val SYNC_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nc-provider-sync").apply { isDaemon = true }
        }

        const val SYNC_CHECK_INTERVAL_MS = 30_000L

        /** Keeps a burst of picker callbacks from asking for one sync after another. */
        val LAST_SYNC_REQUEST = AtomicLong(0L)

        // Intended to match the order AOSP's own test providers use (PLAN 1.4). Unverified, and the
        // picker is expected to read columns by name, so this is a precaution, not a requirement.
        val MEDIA_PROJECTION = arrayOf(
            CloudMediaProviderContract.MediaColumns.ID,
            CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
            CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
            CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
            CloudMediaProviderContract.MediaColumns.WIDTH,
            CloudMediaProviderContract.MediaColumns.HEIGHT,
            CloudMediaProviderContract.MediaColumns.ORIENTATION,
        )

        val ALBUM_PROJECTION = arrayOf(
            CloudMediaProviderContract.AlbumColumns.ID,
            CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
            CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
        )
    }
}
