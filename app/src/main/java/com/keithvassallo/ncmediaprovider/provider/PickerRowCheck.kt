package com.keithvassallo.ncmediaprovider.provider

import com.keithvassallo.ncmediaprovider.data.MediaItem

/**
 * The picker's unwritten rules for a media row (PLAN 2.9, from the AOSP source). Rows that break
 * them are silently dropped, and a malformed media_store_uri aborts the rest of the sync while its
 * position is still saved, so every row is checked here before it is sent.
 */
internal object PickerRowCheck {
    private val MEDIA_STORE_URI = Regex("""content://media/external(_primary)?/(images|video)/media/\d+""")

    /** Null when the row is safe to send, otherwise the reason it isn't. */
    fun problem(item: MediaItem, mediaStoreUri: String?): String? = when {
        item.id.isBlank() -> "no ID"
        item.dateTakenMillis <= 0L -> "no date taken"
        item.sizeBytes <= 0L -> "size ${item.sizeBytes}"
        !item.mimeType.startsWith("image/") && !item.mimeType.startsWith("video/") -> "MIME type ${item.mimeType}"
        item.generation <= 0L -> "generation ${item.generation}"
        item.durationMillis < 0L -> "duration ${item.durationMillis}"
        mediaStoreUri != null && !MEDIA_STORE_URI.matches(mediaStoreUri) -> "media_store_uri $mediaStoreUri"
        else -> null
    }
}
