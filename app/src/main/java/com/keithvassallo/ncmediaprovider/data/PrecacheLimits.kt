package com.keithvassallo.ncmediaprovider.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** How many photos and videos were taken in one month, `yyyy-MM` in local time. */
data class MonthCount(
    val month: String,
    val count: Int,
)

/** What the Storage screen needs to show the pre-cache's limit (#59). */
data class PrecachePlan(
    /** The thumbnail size this phone's picker grid asks for. */
    val sizePx: Int,
    val bytesPerItem: Long,
    /** Photos and videos in the library, newest first per month. */
    val months: List<MonthCount>,
    /** Items taken in the last 1, 3, 6 and 12 months. */
    val recentCounts: Map<Int, Int>,
    val freeBytes: Long,
    /** The most the pre-cache may take: see [PrecacheLimits.ceiling]. */
    val ceilingBytes: Long,
) {
    val total: Int get() = months.sumOf(MonthCount::count)
    val everythingBytes: Long get() = total * bytesPerItem

    /** What [months] of the newest take; 0 months is everything. */
    fun countFor(months: Int): Int = if (months <= 0) total else recentCounts[months] ?: total
}

/**
 * The pre-cache's limit (#59): a date range or a size, kept newest first, and never more than
 * half the free space. The slider is logarithmic, so the last month (tens of MB) and everything
 * (gigabytes on a large library) both get room on it.
 */
object PrecacheLimits {
    const val MIN_BYTES = 25L * 1024L * 1024L
    const val STEPS = 1000

    /** Snapping distance to a date shortcut, in slider steps. */
    const val SNAP_STEPS = 14

    /**
     * Android sets no storage limit per app, so the ceiling is half the free space, counting what
     * the pre-cache already holds as free, or the whole library when that is less.
     */
    fun ceiling(freeBytes: Long, usedBytes: Long, everythingBytes: Long): Long =
        minOf(everythingBytes, (freeBytes + usedBytes) / 2).coerceAtLeast(0L)

    fun position(bytes: Long, ceiling: Long): Int {
        val low = minOf(MIN_BYTES, ceiling)
        if (ceiling <= low || bytes <= low) return if (ceiling <= low) STEPS else 0
        if (bytes >= ceiling) return STEPS
        return (STEPS * ln(bytes.toDouble() / low) / ln(ceiling.toDouble() / low)).roundToInt().coerceIn(0, STEPS)
    }

    fun bytesAt(position: Int, ceiling: Long): Long {
        val low = minOf(MIN_BYTES, ceiling)
        if (ceiling <= low) return ceiling
        return (low * exp(ln(ceiling.toDouble() / low) * position.coerceIn(0, STEPS) / STEPS)).toLong().coerceIn(low, ceiling)
    }

    /** How many of the newest items [bytes] holds. */
    fun itemsFor(bytes: Long, bytesPerItem: Long, total: Int): Int =
        if (bytesPerItem <= 0L) total else minOf(total.toLong(), bytes / bytesPerItem).toInt()

    /** The month the newest [items] reach back to, from [months] newest first; null for none. */
    fun reachesBack(items: Int, months: List<MonthCount>): String? {
        if (items <= 0) return null
        var seen = 0
        for (month in months) {
            seen += month.count
            if (seen >= items) return month.month
        }
        return months.lastOrNull()?.month
    }

    /** The date shortcut (1, 3 or 6 months, 0 for everything) that slider position [at] is close to, if one fits. */
    fun snap(at: Int, plan: PrecachePlan): Int? = SHORTCUTS.firstOrNull { months ->
        val bytes = plan.countFor(months) * plan.bytesPerItem
        bytes <= plan.ceilingBytes && abs(position(bytes, plan.ceilingBytes) - at) <= SNAP_STEPS
    }

    val SHORTCUTS = listOf(1, 3, 6, 0)
}
