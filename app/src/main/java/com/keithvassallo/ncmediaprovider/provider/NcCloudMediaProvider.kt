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
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProvider.CloudMediaSurfaceController
import android.provider.CloudMediaProvider.CloudMediaSurfaceStateChangedCallback
import android.provider.CloudMediaProviderContract
import android.util.Log
import com.keithvassallo.ncmediaprovider.R
import com.keithvassallo.ncmediaprovider.data.LibraryRepository
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.data.Page
import com.keithvassallo.ncmediaprovider.data.People
import com.keithvassallo.ncmediaprovider.data.PickerPerson
import com.keithvassallo.ncmediaprovider.data.RemoteQueryDeferredException
import com.keithvassallo.ncmediaprovider.data.ServerUnreachableException
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
        // process is routinely killed between sessions, so the database opens straight away.
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
            // Signed out, there is no account to name. Without one the picker offers to set an
            // account up through the intent below; a placeholder name made it announce
            // "photos from Not set up" (Phase 4 sign-out test).
            repository.accountName()?.let {
                putString(CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME, it)
            }
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

    /** Video playback in the picker's preview (#36); none until the library is set up. */
    override fun onCreateCloudMediaSurfaceController(
        config: Bundle,
        callback: CloudMediaSurfaceStateChangedCallback,
    ): CloudMediaSurfaceController? {
        enforceSystemCaller()
        if (!repository.isReady) return null
        Log.d(TAG, "onCreateCloudMediaSurfaceController")
        return VideoPreviewController(requireNotNull(context), config, callback, repository)
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        enforceSystemCaller()
        // Only the selected provider is asked for media; an update will deselect it (#31).
        repository.noteSelectedProvider(true)
        val albumId = extras.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID)
        val pageSize = extras.pageSize(DEFAULT_PAGE_SIZE)
        val pageToken = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
        // Present on incremental syncs. MediaProvider rejects a cursor that does not list it as
        // honored, and handles that by wiping the whole cloud catalog and rebuilding it.
        val sinceGeneration = extras.takeIf { it.containsKey(CloudMediaProviderContract.EXTRA_SYNC_GENERATION) }
            ?.getLong(CloudMediaProviderContract.EXTRA_SYNC_GENERATION)
        val collection = queryCollection()
        // An album's photos and videos come whole each time, a page at a time (#43).
        val page = if (albumId == null) {
            safelyQuery { repository.queryMedia(pageSize, pageToken, sinceGeneration) }
        } else {
            safelyQuery { repository.queryAlbumMedia(albumId, pageSize, pageToken) }
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

    /**
     * The user's Nextcloud Photos albums, own and shared (#43), from the database. Only albums
     * with something to show are listed, each with a cover, which the newer picker requires.
     */
    override fun onQueryAlbums(extras: Bundle): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        val albums = runCatching { repository.queryAlbums() }.onFailure(::logProviderFailure).getOrDefault(emptyList())
        Log.d(TAG, "onQueryAlbums -> ${albums.size} albums")
        return MatrixCursor(ALBUM_PROJECTION).apply {
            albums.forEach { album ->
                addRow(arrayOf<Any?>(album.id, album.dateTakenMillis, album.name, album.coverId, album.count))
            }
            this.extras = collectionExtras(collection.id)
        }
    }

    /**
     * What the newer picker may ask for (#45): categories, for its People section, when the
     * last sync found people. Called often and on the picker's thread, so it reads a preference.
     */
    override fun onGetCapabilities(): CloudMediaProviderContract.Capabilities =
        CloudMediaProviderContract.Capabilities.Builder()
            .setMediaCategoriesEnabled(runCatching { repository.peopleAvailable }.getOrDefault(false))
            .build()

    /** One category, People, with the four most photographed faces as its covers. */
    override fun onQueryMediaCategories(parentCategoryId: String?, extras: Bundle, cancellationSignal: CancellationSignal?): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        val people = if (parentCategoryId == null) {
            runCatching { repository.queryPeople() }.onFailure(::logProviderFailure).getOrDefault(emptyList())
        } else {
            emptyList()
        }
        Log.d(TAG, "onQueryMediaCategories parent=$parentCategoryId -> ${if (people.isEmpty()) "none" else "People"}")
        return MatrixCursor(CATEGORY_PROJECTION).apply {
            if (people.isNotEmpty()) {
                val covers = people.take(4).map(PickerPerson::faceCoverId)
                addRow(
                    arrayOf<Any?>(
                        People.CATEGORY_ID,
                        context?.getString(R.string.people_category),
                        CloudMediaProviderContract.MEDIA_CATEGORY_TYPE_PEOPLE_AND_PETS,
                        covers.getOrNull(0), covers.getOrNull(1), covers.getOrNull(2), covers.getOrNull(3),
                    ),
                )
            }
            this.extras = collectionExtras(collection.id)
        }
    }

    /** The people in the People category, a page at a time; the token is the index to carry on from. */
    override fun onQueryMediaSets(mediaCategoryId: String, extras: Bundle, cancellationSignal: CancellationSignal?): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        val pageSize = extras.pageSize(DEFAULT_PAGE_SIZE)
        val start = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)?.toIntOrNull() ?: 0
        val people = if (mediaCategoryId == People.CATEGORY_ID) {
            runCatching { repository.queryPeople() }.onFailure(::logProviderFailure).getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val page = people.drop(start).take(pageSize)
        val next = (start + pageSize).takeIf { it < people.size }?.toString()
        Log.d(TAG, "onQueryMediaSets $mediaCategoryId from $start -> ${page.size} people, next=$next")
        return MatrixCursor(MEDIA_SET_PROJECTION).apply {
            page.forEach { addRow(arrayOf<Any?>(it.id, it.name.ifEmpty { null }, it.faceCoverId, it.count)) }
            this.extras = collectionExtras(collection.id, next).apply {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf(CloudMediaProviderContract.EXTRA_PAGE_SIZE, CloudMediaProviderContract.EXTRA_PAGE_TOKEN),
                )
            }
        }
    }

    /** One person's photos, as media rows; they are all in the library, which the picker requires. */
    override fun onQueryMediaInMediaSet(mediaSetId: String, extras: Bundle, cancellationSignal: CancellationSignal?): Cursor {
        enforceSystemCaller()
        val collection = queryCollection()
        val pageSize = extras.pageSize(DEFAULT_PAGE_SIZE)
        val pageToken = extras.getString(CloudMediaProviderContract.EXTRA_PAGE_TOKEN)
        val page = if (People.isPersonId(mediaSetId)) safelyQuery { repository.queryPersonMedia(mediaSetId, pageSize, pageToken) } else Page(emptyList(), null)
        Log.d(TAG, "onQueryMediaInMediaSet $mediaSetId token=$pageToken -> ${page.items.size} rows, next=${page.nextPageToken}")
        return mediaCursor(page).apply {
            this.extras = collectionExtras(collection.id, page.nextPageToken).apply {
                putStringArrayList(
                    ContentResolver.EXTRA_HONORED_ARGS,
                    arrayListOf(CloudMediaProviderContract.EXTRA_PAGE_SIZE, CloudMediaProviderContract.EXTRA_PAGE_TOKEN),
                )
            }
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
        // Which sizes the picker asks for decides what the thumbnail pre-cache keeps (#59).
        if (REQUESTED_PREVIEW_SIZES.add("${size.x}x${size.y}/$thumbnailOnly")) {
            Log.i(TAG, "onOpenPreview size ${size.x}x${size.y}, thumbnail=$thumbnailOnly")
        }
        return try {
            repository.openPreview(mediaId, size, thumbnailOnly, cancellationSignal)
        } catch (error: Exception) {
            if (error is RemoteQueryDeferredException || error is ServerUnreachableException) {
                // Expected offline, for every tile on screen; not worth a warning each.
                Log.d(TAG, "onOpenPreview $mediaId not fetched: ${error.message}")
            } else if (error is OperationCanceledException || cancellationSignal?.isCanceled == true) {
                // The tile scrolled away. OkHttp reports that as a plain IOException ("Canceled").
                Log.d(TAG, "onOpenPreview $mediaId canceled")
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
                // Matched ahead of time and stored with the row (#23): MediaProvider keeps rows
                // as sent, so a match worked out here, when the picker asks, could never be revised.
                val localUri = item.mediaStoreUri
                PickerRowCheck.problem(item, localUri)?.let { problem ->
                    Log.w(TAG, "Dropped row ${item.id}: $problem")
                    return@forEach
                }
                addRow(
                    arrayOf<Any?>(
                        item.id,
                        item.dateTakenMillis,
                        // The generation this row last changed in, so an incremental sync only
                        // receives what changed (#14).
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
                        // reported sizes are in display orientation (#3), so nothing to rotate.
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
     * job, which keeps network access after this call returns (#16), and tells MediaProvider
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
        private val REQUESTED_PREVIEW_SIZES = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

        // Intended to match the order AOSP's own test providers use (#9). Unverified, and the
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

        val CATEGORY_PROJECTION = arrayOf(
            CloudMediaProviderContract.MediaCategoryColumns.ID,
            CloudMediaProviderContract.MediaCategoryColumns.DISPLAY_NAME,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_CATEGORY_TYPE,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID1,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID2,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID3,
            CloudMediaProviderContract.MediaCategoryColumns.MEDIA_COVER_ID4,
        )

        val MEDIA_SET_PROJECTION = arrayOf(
            CloudMediaProviderContract.MediaSetColumns.ID,
            CloudMediaProviderContract.MediaSetColumns.DISPLAY_NAME,
            CloudMediaProviderContract.MediaSetColumns.MEDIA_COVER_ID,
            CloudMediaProviderContract.MediaSetColumns.MEDIA_COUNT,
        )

        /**
         * In AOSP's order (`AlbumColumns.ALL_PROJECTION`): the older picker's album cursor wrapper
         * maps columns by name for text only, so numbers are read by position.
         */
        val ALBUM_PROJECTION = arrayOf(
            CloudMediaProviderContract.AlbumColumns.ID,
            CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
        )
    }
}
