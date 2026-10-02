package com.keithvassallo.ncmediaprovider.local

import kotlin.math.abs

/** A photo or video on the phone, as MediaStore lists it. */
data class LocalPhoto(
    /** MediaStore's row ID; lower IDs win ties, so the choice is stable. */
    val id: Long,
    /** `content://media/external/images/media/<id>` or the video equivalent. */
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    /** 0 when MediaStore has no date taken, as for most screenshots. */
    val dateTakenMillis: Long,
    val mimeType: String,
)

/** The fields of a library row that matching needs (a Room projection of the media table). */
data class CloudPhoto(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val dateTakenMillis: Long,
    val mimeType: String,
    /** The phone copy the row was matched to last time, if any. */
    val mediaStoreUri: String?,
)

/**
 * Pairs library rows with the phone's own copies (PLAN 3.2), so the picker shows each photo once.
 *
 * Rules, strongest first; among several phone candidates the lowest MediaStore ID wins:
 * 1. Same name (ignoring the case of ASCII letters) and size. The Nextcloud app keeps both when it
 *    auto-uploads, which covered every match on Keith's phone.
 * 2. Same size and date taken, for uploads that were renamed.
 * 3. Same name, kind (image or video) and date taken, for copies edited or re-encoded on one side.
 *
 * Several rows may name the same phone item, so a photo stored twice in Nextcloud still shows once:
 * MediaProvider keeps every cloud row naming a visible phone item as hidden. (It first tries the row
 * as visible and logs `UNIQUE constraint failed: media.local_id, media.is_visible` at verbose level
 * when that fails; that is its normal path, not an error.)
 *
 * A row keeps last time's match while that phone item still exists and still fits a rule, so a new
 * candidate never moves a match that is already working.
 */
object LocalMatcher {
    /** Library row ID to the URI of its phone copy, for the rows that have one. */
    fun match(cloud: List<CloudPhoto>, local: List<LocalPhoto>): Map<String, String> {
        val localByUri = local.associateBy(LocalPhoto::uri)
        val byId = local.sortedBy(LocalPhoto::id)
        val byNameAndSize = byId.groupBy { NameAndSize(nameKey(it.name), it.sizeBytes) }
        val bySize = byId.groupBy(LocalPhoto::sizeBytes)
        val byName = byId.groupBy { nameKey(it.name) }

        fun bestFor(row: CloudPhoto): LocalPhoto? {
            row.mediaStoreUri?.let(localByUri::get)?.takeIf { fitsAnyRule(row, it) }?.let { return it }
            if (row.sizeBytes <= 0L) return null
            byNameAndSize[NameAndSize(nameKey(row.fileName), row.sizeBytes)]?.let { candidates ->
                return candidates.firstOrNull { sameMoment(row.dateTakenMillis, it.dateTakenMillis) } ?: candidates.first()
            }
            bySize[row.sizeBytes]?.firstOrNull { sameMoment(row.dateTakenMillis, it.dateTakenMillis) }?.let { return it }
            return byName[nameKey(row.fileName)]?.firstOrNull { sameKind(row, it) && sameMoment(row.dateTakenMillis, it.dateTakenMillis) }
        }

        val result = HashMap<String, String>()
        for (row in cloud) bestFor(row)?.let { result[row.id] = it.uri }
        return result
    }

    /**
     * The name as matching compares it: ASCII letters lower-cased and nothing else, exactly as
     * SQLite's `lower()` does, so the database can pick out the candidate rows (see
     * [com.keithvassallo.ncmediaprovider.data.LibraryStore.matchCandidates]).
     */
    fun nameKey(name: String): String {
        if (name.none { it in 'A'..'Z' }) return name
        return String(CharArray(name.length) { i -> name[i].let { if (it in 'A'..'Z') it + ('a' - 'A') else it } })
    }

    /**
     * Equal within 2 s, give or take a whole time-zone offset (a multiple of 15 minutes, up to
     * 14 hours). Nextcloud and Android read a photo's EXIF date in different zones: every match on
     * Keith's phone was exactly an hour apart (Phase 3).
     */
    internal fun sameMoment(a: Long, b: Long): Boolean {
        if (a <= 0L || b <= 0L) return false
        val difference = abs(a - b)
        if (difference > MAX_ZONE_OFFSET_MS + TOLERANCE_MS) return false
        val rest = difference % ZONE_STEP_MS
        return rest <= TOLERANCE_MS || ZONE_STEP_MS - rest <= TOLERANCE_MS
    }

    private fun fitsAnyRule(row: CloudPhoto, photo: LocalPhoto): Boolean {
        val sameName = nameKey(row.fileName) == nameKey(photo.name)
        val sameDate = sameMoment(row.dateTakenMillis, photo.dateTakenMillis)
        return (sameName && row.sizeBytes == photo.sizeBytes) ||
            (row.sizeBytes == photo.sizeBytes && sameDate) ||
            (sameName && sameKind(row, photo) && sameDate)
    }

    private fun sameKind(row: CloudPhoto, photo: LocalPhoto) =
        row.mimeType.substringBefore('/') == photo.mimeType.substringBefore('/')

    private data class NameAndSize(val name: String, val size: Long)

    private const val TOLERANCE_MS = 2_000L
    private const val ZONE_STEP_MS = 15L * 60L * 1_000L
    private const val MAX_ZONE_OFFSET_MS = 14L * 60L * 60L * 1_000L
}
