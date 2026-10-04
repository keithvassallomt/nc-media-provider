package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preview sizes (#33) and the pre-cache following the picker's grid (#59). */
class PreviewSizesTest {
    @Test
    fun `tiles get the smallest preview that serves them`() {
        // Keith's fold asks for 264 to 291 px, the stock Pixel 11 Pro for 358.
        assertEquals(256, PreviewSizes.forTile(264))
        assertEquals(256, PreviewSizes.forTile(300))
        assertEquals(512, PreviewSizes.forTile(358))
        assertEquals(512, PreviewSizes.forTile(600))
        assertEquals(1024, PreviewSizes.forTile(1712))
    }

    @Test
    fun `the newer picker's full-screen thumbnails are not grid tiles`() {
        assertTrue(PreviewSizes.isGridTile(358))
        assertFalse(PreviewSizes.isGridTile(1712))
    }

    @Test
    fun `the size asked for most wins once a window is full`() {
        val tally = GridSizeTally(window = 10)
        // The stock Pixel: grid tiles at 512 px, and a few face covers and icons at 256.
        val sizes = List(7) { 512 } + List(3) { 256 }
        val results = sizes.map { tally.record(it, current = 256) }
        assertEquals(List(9) { null }, results.dropLast(1))
        assertEquals(512, results.last())
    }

    @Test
    fun `album covers alone don't move a fold's pre-cache up`() {
        val tally = GridSizeTally(window = 10)
        // Keith's fold: grid tiles at 256 px, album covers at 308 px, which map to 512.
        val sizes = List(8) { 256 } + List(2) { 512 }
        assertNull(sizes.map { tally.record(it, current = 256) }.last())
    }

    @Test
    fun `the pre-cache never moves down`() {
        val tally = GridSizeTally(window = 4)
        repeat(3) { assertNull(tally.record(256, current = 512)) }
        assertNull(tally.record(256, current = 512))
        // A new window starts counting afresh.
        repeat(3) { assertNull(tally.record(1024, current = 512)) }
        assertEquals(1024, tally.record(1024, current = 512))
    }
}
