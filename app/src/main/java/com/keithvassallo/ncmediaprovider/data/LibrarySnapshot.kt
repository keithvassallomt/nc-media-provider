package com.keithvassallo.ncmediaprovider.data

/**
 * One complete listing of the library, newest first. Every item reports the snapshot's generation:
 * until Phase 2 adds per-row generations, any change re-sends the whole library to the picker.
 */
class LibrarySnapshot(val generation: Long, val items: List<MediaItem>) {
    private val byId = items.associateBy(MediaItem::id)

    fun find(id: String): MediaItem? = byId[id]

    /** Page tokens are offsets into [items], valid only within this snapshot. */
    fun page(pageSize: Int, pageToken: String?, sinceGeneration: Long?): Page<MediaItem> {
        if (sinceGeneration != null && sinceGeneration >= generation) return Page(emptyList(), null)
        val start = pageToken?.toIntOrNull()?.coerceIn(0, items.size) ?: 0
        val end = minOf(start + pageSize.coerceAtLeast(1), items.size)
        return Page(items.subList(start, end), end.takeIf { it < items.size }?.toString())
    }
}
