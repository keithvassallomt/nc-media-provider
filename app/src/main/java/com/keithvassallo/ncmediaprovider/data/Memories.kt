package com.keithvassallo.ncmediaprovider.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.keithvassallo.ncmediaprovider.local.LocalMatcher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One file as Memories indexed it (PLAN 6.2). The table mirrors Memories' timeline, which is read a
 * day at a time, so a day whose file count hasn't changed needn't be read again.
 */
@Entity(tableName = "memories_file", indices = [Index(value = ["dayId"])])
data class MemoriesFile(
    @PrimaryKey val id: String,
    val etag: String,
    /** Memories' day: days since 1970, in the photo's local time. */
    val dayId: Int,
    /** Memories' `epoch`: the capture time with the camera's time zone applied. */
    val dateTakenMillis: Long,
    val width: Int,
    val height: Int,
    /** The ID pairing a live photo with its video; null for none, or when the motion is inside the photo. */
    val liveId: String?,
    val isVideo: Boolean,
)

/** How many files a timeline day held when it was last read. */
@Entity(tableName = "memories_day")
data class MemoriesDay(
    @PrimaryKey val dayId: Int,
    val count: Int,
)

/**
 * Parsing and planning for the Memories layer (PLAN 6.1 to 6.3), kept apart from HTTP so it can be
 * tested against saved responses: Memories' API is internal and undocumented, and may change.
 */
internal object MemoriesApi {
    /** The Memories versions this layer was tested against (8.1 and 9.0, Phase 6); others are left alone. */
    private const val MIN_MAJOR = 8
    private const val MAX_MAJOR = 9

    /** Memories marks a photo with its motion inside (a Pixel motion photo, say) with this prefix. */
    private const val SELF_CONTAINED_LIVE_PREFIX = "self__"

    const val MAX_FILES_PER_REQUEST = 1_000
    const val MAX_DAYS_PER_REQUEST = 100

    /** The version from `api/describe`, or null if the response has none. */
    fun parseVersion(json: String): String? =
        runCatching { JSONObject(json).optString("version", "") }.getOrNull()?.takeIf(String::isNotEmpty)

    fun isSupported(version: String): Boolean =
        version.substringBefore('.').toIntOrNull()?.let { it in MIN_MAJOR..MAX_MAJOR } ?: false

    /** The timeline's days and how many files each holds, from `api/days`. */
    @Throws(JSONException::class)
    fun parseDays(json: String): Map<Int, Int> {
        val days = JSONArray(json)
        return (0 until days.length()).associate { i ->
            val day = days.getJSONObject(i)
            day.getInt("dayid") to day.getInt("count")
        }
    }

    /**
     * The files from `api/days/<ids>`. A file missing its ID, etag, day or capture time is skipped,
     * so a change in Memories' format falls back to core values file by file instead of failing.
     */
    @Throws(JSONException::class)
    fun parseFiles(json: String): List<MemoriesFile> {
        val files = JSONArray(json)
        return (0 until files.length()).mapNotNull { i -> files.optJSONObject(i)?.let(::parseFile) }
    }

    private fun parseFile(file: JSONObject): MemoriesFile? {
        val id = file.optLong("fileid", 0L).takeIf { it > 0L } ?: return null
        val etag = file.optString("etag", "").takeIf(String::isNotEmpty) ?: return null
        val dayId = file.optInt("dayid", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return null
        val epoch = file.optLong("epoch", 0L).takeIf { it > 0L } ?: return null
        return MemoriesFile(
            id = id.toString(),
            etag = etag,
            dayId = dayId,
            dateTakenMillis = epoch * 1_000L,
            width = file.optInt("w", 0),
            height = file.optInt("h", 0),
            liveId = file.optString("liveid", "").takeIf { it.isNotEmpty() && !it.startsWith(SELF_CONTAINED_LIVE_PREFIX) },
            isVideo = file.optInt("isvideo", 0) == 1,
        )
    }

    /**
     * The days to read again: new days, days whose count changed, and [staleDays] (days holding a
     * file whose etag has since moved on). An edit can change a file without changing any count, so
     * the full read that comes with each full listing catches whatever this misses.
     */
    fun changedDays(current: Map<Int, Int>, stored: Map<Int, Int>, staleDays: Collection<Int>): Set<Int> =
        current.filter { (day, count) -> stored[day] != count }.keys + staleDays.filter(current::containsKey)

    /** [days] grouped into requests of up to [MAX_FILES_PER_REQUEST] files and [MAX_DAYS_PER_REQUEST] days, newest first. */
    fun requestBatches(days: Collection<Int>, counts: Map<Int, Int>): List<List<Int>> {
        val batches = ArrayList<List<Int>>()
        var batch = ArrayList<Int>()
        var files = 0
        for (day in days.sortedDescending()) {
            val count = counts[day] ?: 0
            if (batch.isNotEmpty() && (files + count > MAX_FILES_PER_REQUEST || batch.size >= MAX_DAYS_PER_REQUEST)) {
                batches += batch
                batch = ArrayList()
                files = 0
            }
            batch += day
            files += count
        }
        if (batch.isNotEmpty()) batches += batch
        return batches
    }

    /**
     * The videos among [items] that are the moving half of a live photo, by Memories' pairing:
     * Memories leaves such a video out of its timeline and gives the photo a live-photo ID, and the
     * two share a folder and a name. Core Nextcloud hides most of these itself, but missed 277 short
     * MOVs on Keith's server (Phase 6), which the picker showed as videos of their own.
     */
    fun liveHalves(items: Collection<MediaItem>, memories: (String) -> MemoriesFile?): Set<String> {
        val livePhotos = items.filter { !it.isVideo && memories(it.id)?.liveId != null }.mapTo(HashSet(), ::pairKey)
        if (livePhotos.isEmpty()) return emptySet()
        return items.filter { it.isVideo && memories(it.id) == null && pairKey(it) in livePhotos }.mapTo(HashSet(), MediaItem::id)
    }

    /** A live photo's two halves share a folder and a name, compared without case. */
    fun pairKey(item: MediaItem): String = item.folder + LocalMatcher.nameKey(item.fileName.substringBeforeLast('.'))
}
