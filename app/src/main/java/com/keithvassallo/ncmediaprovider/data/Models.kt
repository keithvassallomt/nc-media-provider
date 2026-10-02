package com.keithvassallo.ncmediaprovider.data

/** One photo or video in the user's Nextcloud library, keyed by its Nextcloud file ID. */
data class MediaItem(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val durationMillis: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val isFavorite: Boolean = false,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class Page<T>(
    val items: List<T>,
    val nextPageToken: String?,
)
