package com.keithvassallo.ncmediaprovider.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video in the user's Nextcloud library, keyed by its Nextcloud file ID. Stored as a
 * row of the `media` table with the generation it last changed in (PLAN 2.1).
 *
 * [dateTakenMillis], [width] and [height] are what the picker gets: Memories' values when it has
 * indexed the file, else a video's own recording time, else the listing's (see [resolved]). The
 * listing's are kept beside them, so turning Memories off restores them (PLAN 6.2).
 */
@Entity(
    tableName = "media",
    // The last index serves the photo keyboard's newest-first browsing (PLAN 4.8).
    indices = [Index(value = ["generation", "id"]), Index(value = ["folder"]), Index(value = ["dateTakenMillis", "id"])],
)
data class MediaItem(
    @PrimaryKey val id: String,
    /** Percent-encoded WebDAV path, as the server returned it. */
    val href: String,
    val etag: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val dateTakenMillis: Long,
    val durationMillis: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val isFavorite: Boolean = false,
    val generation: Long = 0L,
    /** Decoded path of the folder holding the file, ending in '/' (see [parentFolderKey]). */
    val folder: String = "",
    /**
     * The phone's own copy, which makes the picker show this photo once (PLAN 3.2). Set by
     * [com.keithvassallo.ncmediaprovider.local.LocalMatcher], never by a listing.
     */
    val mediaStoreUri: String? = null,
    /** The listing's date taken: Nextcloud's, from EXIF or the file name, else the modification time. */
    @ColumnInfo(defaultValue = "0")
    val listedDateMillis: Long = dateTakenMillis,
    @ColumnInfo(defaultValue = "0")
    val listedWidth: Int = width,
    @ColumnInfo(defaultValue = "0")
    val listedHeight: Int = height,
    /**
     * A video's recording time from its own header (PLAN 6.0): 0 until read, [NOT_IN_HEADER] when
     * the header has none. Like [durationMillis], read on the phone and kept while the etag is unchanged.
     */
    @ColumnInfo(defaultValue = "0")
    val recordedMillis: Long = 0L,
    /**
     * The video half of a live photo by Memories' pairing (PLAN 6.2). The row is kept but reported
     * to the picker as deleted, so turning Memories off brings it back without listing again.
     */
    @ColumnInfo(defaultValue = "0")
    val isLiveVideo: Boolean = false,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    companion object {
        /** For [durationMillis] and [recordedMillis]: the header couldn't be read, or holds no plausible value. */
        const val NOT_IN_HEADER = -1L
    }
}

/**
 * The values the picker gets (PLAN 6.0 and 6.2): Memories' when it indexed the file as it is now
 * (same etag), then a video's recording time from its header, then the listing's. Memories reads
 * the camera's time zone, where core Nextcloud reads EXIF times as the server's local time: an hour
 * or two out for most of Keith's JPEGs, and the upload time for nearly every HEIC.
 */
internal fun MediaItem.resolved(memories: MemoriesFile?): MediaItem {
    val indexed = memories?.takeIf { it.etag == etag }
    val size = indexed?.takeIf { it.width > 0 && it.height > 0 }
    return copy(
        dateTakenMillis = indexed?.dateTakenMillis?.takeIf { it > 0L } ?: recordedMillis.takeIf { it > 0L } ?: listedDateMillis,
        width = size?.width ?: listedWidth,
        height = size?.height ?: listedHeight,
    )
}

data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
)
