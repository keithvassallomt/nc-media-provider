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
import java.util.concurrent.atomic.AtomicBoolean

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
                if (repository.hasAccount) repository.warmLocalMediaIndex()
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
        return mediaCursor(page, collection.generation).apply {
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
        return MatrixCursor(arrayOf(CloudMediaProviderContract.MediaColumns.ID)).apply {
            repository.deletedSince(previousGeneration).forEach { addRow(arrayOf(it)) }
            this.extras = collectionExtras(collection.id).apply {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf(CloudMediaProviderContract.EXTRA_SYNC_GENERATION),
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
        return try {
            repository.openOriginal(mediaId, cancellationSignal)
        } catch (error: Exception) {
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
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Unable to open preview").apply { initCause(error) }
        }
    }

    private fun mediaCursor(page: Page<MediaItem>, generation: Long): MatrixCursor =
        MatrixCursor(MEDIA_PROJECTION).apply {
            page.items.forEach { item ->
                val localUri = runCatching { repository.localUri(item) }.getOrNull()
                addRow(
                    arrayOf<Any?>(
                        item.id,
                        item.mimeType,
                        item.dateTakenMillis,
                        generation,
                        item.sizeBytes,
                        item.durationMillis.takeIf { it > 0L },
                        if (item.isFavorite) 1 else 0,
                        item.width.takeIf { it > 0 },
                        item.height.takeIf { it > 0 },
                        // Nextcloud previews arrive already rotated, with EXIF stripped, and its
                        // reported sizes are in display orientation (PLAN 0.2), so nothing to rotate.
                        null,
                        standardMimeExtension(item.mimeType),
                        localUri?.toString(),
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

    private fun scheduleSyncCheck() {
        val repo = repository
        if (!SYNC_IN_FLIGHT.compareAndSet(false, true)) return
        SYNC_EXECUTOR.execute {
            val changed = try {
                runCatching { repo.pollChanges() }.getOrDefault(false)
            } finally {
                SYNC_IN_FLIGHT.set(false)
            }
            if (changed) {
                // The collection id and generation just moved, so the value cached for this sync
                // pass is stale. Dropping it makes the next cursor advertise the new collection,
                // which is how MediaProvider is told to restart rather than commit mixed data.
                advertisedCollection = null
                repo.notifyPickerOfChanges()
            }
        }
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

        /** Keeps a burst of picker callbacks from queueing one sync pass behind another. */
        val SYNC_IN_FLIGHT = AtomicBoolean(false)

        val MEDIA_PROJECTION = arrayOf(
            CloudMediaProviderContract.MediaColumns.ID,
            CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.MediaColumns.SYNC_GENERATION,
            CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
            CloudMediaProviderContract.MediaColumns.WIDTH,
            CloudMediaProviderContract.MediaColumns.HEIGHT,
            CloudMediaProviderContract.MediaColumns.ORIENTATION,
            CloudMediaProviderContract.MediaColumns.STANDARD_MIME_TYPE_EXTENSION,
            CloudMediaProviderContract.MediaColumns.MEDIA_STORE_URI,
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
