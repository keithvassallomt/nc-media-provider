package com.keithvassallo.ncmediaprovider.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One photo or video in the user's Nextcloud library, keyed by its Nextcloud file ID. Stored as a
 * row of the `media` table with the generation it last changed in (PLAN 2.1).
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
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
)
