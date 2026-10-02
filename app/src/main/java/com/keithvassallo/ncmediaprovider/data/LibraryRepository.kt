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
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Everything the picker reads goes through here. In Phase 1.1 the library is always empty:
 * listing (1.3), loading and media delivery (1.4) fill it in.
 */
class LibraryRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val credentials = CredentialStore(appContext)
    private val diskCache = MediaDiskCache(appContext)
    private val localMedia = LocalMediaIndex(appContext)

    /** Plain preferences only, never the Keystore: safe on the picker's 100 ms collection-info path. */
    val hasAccount: Boolean get() = credentials.account() != null

    val hasFullLocalMediaAccess: Boolean get() = localMedia.hasFullAccess()

    fun account(): CredentialStore.SavedAccount? = credentials.account()

    fun collectionId(): String {
        val account = credentials.account() ?: return "nc-unconfigured-v1"
        val identity = "${account.baseUrl}|${account.userId}|$COLLECTION_FORMAT"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return "nc-$digest"
    }

    fun generation(): Long = 0L

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

    /** Returns true when the library changed. Loading arrives in 1.4, change detection in Phase 2. */
    fun pollChanges(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun queryMedia(pageSize: Int, pageToken: String?, sinceGeneration: Long?): Page<MediaItem> =
        Page(emptyList(), null)

    @Suppress("UNUSED_PARAMETER")
    fun deletedSince(generation: Long): List<String> = emptyList()

    fun localUri(item: MediaItem): Uri? =
        localMedia.find(item.fileName, item.sizeBytes, item.dateTakenMillis, item.mimeType)

    fun invalidateLocalMedia() = localMedia.invalidate()

    fun warmLocalMediaIndex() = localMedia.warm()

    fun cacheStats(): CacheStats = diskCache.stats()

    @Throws(FileNotFoundException::class)
    fun openOriginal(mediaId: String, cancellationSignal: CancellationSignal?): ParcelFileDescriptor =
        throw FileNotFoundException("No media $mediaId: delivery arrives in Phase 1.4")

    @Throws(FileNotFoundException::class)
    fun openPreview(
        mediaId: String,
        requestedSize: Point,
        thumbnailOnly: Boolean,
        cancellationSignal: CancellationSignal?,
    ): AssetFileDescriptor = throw FileNotFoundException("No preview for $mediaId: delivery arrives in Phase 1.4")

    companion object {
        private const val TAG = "LibraryRepository"

        /** Bump to make MediaProvider drop its cached copy of every user's library. */
        private const val COLLECTION_FORMAT = "v1"

        @Volatile
        private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
            instance ?: LibraryRepository(context).also { instance = it }
        }
    }
}
