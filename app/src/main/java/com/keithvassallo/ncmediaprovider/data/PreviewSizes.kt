package com.keithvassallo.ncmediaprovider.data

/**
 * Which preview to fetch for a tile (#33). The server makes 64, 256 and 1024 px previews
 * natively; a tile gets the smallest of 256, 512 and 1024 that serves it with little enlargement.
 */
internal object PreviewSizes {
    const val SMALL_PX = 256
    const val MEDIUM_PX = 512
    const val LARGE_PX = 1024

    /**
     * The picker's grid asks for 264 to 291 px on Keith's fold (Phase 5); sending those to the
     * 1024 px bucket downloaded ten times the data per tile, so 256 px serves tiles up to 300 px.
     */
    private const val SMALL_MAX_TILE_PX = 300
    private const val MEDIUM_MAX_TILE_PX = 600

    fun forTile(requestedPx: Int): Int = when {
        requestedPx <= SMALL_MAX_TILE_PX -> SMALL_PX
        requestedPx <= MEDIUM_MAX_TILE_PX -> MEDIUM_PX
        else -> LARGE_PX
    }

    /** Grid tiles and covers; anything larger is the newer picker's full-screen preview. */
    fun isGridTile(requestedPx: Int): Boolean = requestedPx <= MEDIUM_MAX_TILE_PX

    /**
     * Roughly what one thumbnail takes, with some margin: on Keith's server (2026-10-03) JPEG and
     * HEIC previews averaged 11 KB at 256 px and 65 KB at 512.
     */
    fun typicalBytes(sizePx: Int): Long = when {
        sizePx <= SMALL_PX -> 15L * 1024L
        sizePx <= MEDIUM_PX -> 70L * 1024L
        else -> 250L * 1024L
    }
}

/**
 * Which preview size this phone's picker grid uses, from the thumbnails it asks for (#59).
 * After each [window] grid requests the size asked for most wins, but the pre-cache only ever
 * moves up: a larger thumbnail serves a smaller tile, so a foldable whose screens differ settles
 * on the larger instead of fetching everything again each time it is unfolded.
 */
internal class GridSizeTally(private val window: Int = 100) {
    private val counts = HashMap<Int, Int>()
    private var seen = 0

    /** Notes one grid request at [sizePx]; returns a size larger than [current] when a window ends on one. */
    @Synchronized
    fun record(sizePx: Int, current: Int): Int? {
        counts[sizePx] = (counts[sizePx] ?: 0) + 1
        if (++seen < window) return null
        val leader = counts.maxBy { it.value }.key
        counts.clear()
        seen = 0
        return leader.takeIf { it > current }
    }
}
