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
 * Each phone item goes to at most one row: MediaProvider refuses a second cloud row naming the same
 * local item (`UNIQUE constraint failed: media.local_id, media.is_visible`, 24 times in Phase 1.5).
 * Rules, strongest first, each pass taking only what the earlier ones left:
 * 1. Same name (ignoring the case of ASCII letters) and size. The Nextcloud app keeps both when it auto-uploads, which
 *    covered every match on Keith's phone.
 * 2. Same size and date taken, for uploads that were renamed.
 * 3. Same name, kind (image or video) and date taken, for copies edited or re-encoded on one side.
 *
 * A row keeps last time's match while that phone item still exists and still fits a rule, so a new
 * candidate never moves a match that is already working.
 */
object LocalMatcher {
    /** Library row ID to the URI of its phone copy, for the rows that have one. */
    fun match(cloud: List<CloudPhoto>, local: List<LocalPhoto>): Map<String, String> {
        val result = HashMap<String, String>()
        val claimed = HashSet<Long>()
        val localByUri = local.associateBy(LocalPhoto::uri)
        val rows = cloud.sortedBy(CloudPhoto::id)

        for (row in rows) {
            val previous = row.mediaStoreUri?.let(localByUri::get) ?: continue
            if (previous.id !in claimed && fitsAnyRule(row, previous)) claim(row, previous, result, claimed)
        }

        val byNameAndSize = local.groupBy { NameAndSize(nameKey(it.name), it.sizeBytes) }
        val bySize = local.groupBy(LocalPhoto::sizeBytes)
        val byName = local.groupBy { nameKey(it.name) }
        val passes: List<(CloudPhoto) -> LocalPhoto?> = listOf(
            { row ->
                val candidates = byNameAndSize[NameAndSize(nameKey(row.fileName), row.sizeBytes)].unclaimed(claimed)
                candidates.firstOrNull { sameMoment(row.dateTakenMillis, it.dateTakenMillis) } ?: candidates.firstOrNull()
            },
            { row ->
                bySize[row.sizeBytes].unclaimed(claimed).firstOrNull { sameMoment(row.dateTakenMillis, it.dateTakenMillis) }
            },
            { row ->
                byName[nameKey(row.fileName)].unclaimed(claimed)
                    .firstOrNull { sameKind(row, it) && sameMoment(row.dateTakenMillis, it.dateTakenMillis) }
            },
        )
        for (pass in passes) {
            for (row in rows) {
                if (row.id in result || row.sizeBytes <= 0L) continue
                pass(row)?.let { claim(row, it, result, claimed) }
            }
        }
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

    private fun claim(row: CloudPhoto, photo: LocalPhoto, result: MutableMap<String, String>, claimed: MutableSet<Long>) {
        result[row.id] = photo.uri
        claimed += photo.id
    }

    private fun List<LocalPhoto>?.unclaimed(claimed: Set<Long>): List<LocalPhoto> =
        orEmpty().filter { it.id !in claimed }.sortedBy(LocalPhoto::id)

    private fun sameKind(row: CloudPhoto, photo: LocalPhoto) =
        row.mimeType.substringBefore('/') == photo.mimeType.substringBefore('/')

    private data class NameAndSize(val name: String, val size: Long)

    private const val TOLERANCE_MS = 2_000L
    private const val ZONE_STEP_MS = 15L * 60L * 1_000L
    private const val MAX_ZONE_OFFSET_MS = 14L * 60L * 60L * 1_000L
}
