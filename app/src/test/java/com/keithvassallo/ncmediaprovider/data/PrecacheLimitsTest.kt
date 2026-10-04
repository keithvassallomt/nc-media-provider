package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pre-cache's limit on the Storage screen (#59). */
class PrecacheLimitsTest {
    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    // Keith's library at 512 px: 18,348 items, about 70 KB each.
    private val plan = PrecachePlan(
        sizePx = 512,
        bytesPerItem = 70L * 1024L,
        months = listOf(MonthCount("2026-09", 645), MonthCount("2026-08", 459), MonthCount("2026-07", 56), MonthCount("2015-01", 17_188)),
        recentCounts = mapOf(1 to 645, 3 to 1_160, 6 to 2_841),
        freeBytes = 41 * gib,
        ceilingBytes = 18_348L * 70L * 1024L,
    )

    @Test
    fun `the ceiling is half the free space, counting the pre-cache's own, or the whole library if less`() {
        assertEquals(gib, PrecacheLimits.ceiling(freeBytes = 41 * gib, usedBytes = 0L, everythingBytes = gib))
        assertEquals(800 * mib, PrecacheLimits.ceiling(freeBytes = 1_200 * mib, usedBytes = 400 * mib, everythingBytes = gib))
    }

    @Test
    fun `the slider runs from 25 MB to the ceiling, and back again`() {
        val ceiling = plan.ceilingBytes
        assertEquals(0, PrecacheLimits.position(PrecacheLimits.MIN_BYTES, ceiling))
        assertEquals(PrecacheLimits.STEPS, PrecacheLimits.position(ceiling, ceiling))
        assertEquals(PrecacheLimits.MIN_BYTES, PrecacheLimits.bytesAt(0, ceiling))
        assertEquals(ceiling, PrecacheLimits.bytesAt(PrecacheLimits.STEPS, ceiling))
        val middle = PrecacheLimits.bytesAt(500, ceiling)
        assertEquals(500, PrecacheLimits.position(middle, ceiling))
        // Logarithmic: the middle is far below half the ceiling, so small sizes get room.
        assertTrue(middle < ceiling / 4)
    }

    @Test
    fun `a ceiling below 25 MB pins the slider at the top`() {
        assertEquals(PrecacheLimits.STEPS, PrecacheLimits.position(10 * mib, 10 * mib))
        assertEquals(10 * mib, PrecacheLimits.bytesAt(300, 10 * mib))
    }

    @Test
    fun `the estimate says how far back the newest items reach`() {
        assertEquals("2026-09", PrecacheLimits.reachesBack(600, plan.months))
        assertEquals("2026-08", PrecacheLimits.reachesBack(645 + 1, plan.months))
        assertEquals("2015-01", PrecacheLimits.reachesBack(1_000_000, plan.months))
        assertNull(PrecacheLimits.reachesBack(0, plan.months))
        assertEquals(1_160, PrecacheLimits.itemsFor(1_160L * 70L * 1024L, 70L * 1024L, plan.total))
        assertEquals(plan.total, PrecacheLimits.itemsFor(100 * gib, 70L * 1024L, plan.total))
    }

    @Test
    fun `the thumb snaps to a date shortcut close by, and not to one that doesn't fit`() {
        val threeMonths = PrecacheLimits.position(1_160L * 70L * 1024L, plan.ceilingBytes)
        assertEquals(3, PrecacheLimits.snap(threeMonths + 5, plan))
        assertEquals(0, PrecacheLimits.snap(PrecacheLimits.STEPS, plan))
        assertNull(PrecacheLimits.snap(threeMonths + 60, plan))
        val tight = plan.copy(ceilingBytes = 100 * mib)
        assertNull(PrecacheLimits.snap(PrecacheLimits.STEPS, tight))
    }
}
