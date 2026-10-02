package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySnapshotTest {
    private val snapshot = LibrarySnapshot(
        generation = 3,
        items = (1..5).map { MediaItem("$it", "/f/$it.jpg", "e$it", "$it.jpg", "image/jpeg", 10, 1_000, 1_000) },
    )

    @Test
    fun `pages through the library with offset tokens`() {
        val first = snapshot.page(2, null, null)
        assertEquals(listOf("1", "2"), first.items.map(MediaItem::id))
        val second = snapshot.page(2, first.nextPageToken, null)
        assertEquals(listOf("3", "4"), second.items.map(MediaItem::id))
        val last = snapshot.page(2, second.nextPageToken, null)
        assertEquals(listOf("5"), last.items.map(MediaItem::id))
        assertNull(last.nextPageToken)
    }

    @Test
    fun `an up-to-date picker gets nothing`() {
        assertEquals(0, snapshot.page(10, null, sinceGeneration = 3).items.size)
    }

    @Test
    fun `an older picker gets everything`() {
        assertEquals(5, snapshot.page(10, null, sinceGeneration = 2).items.size)
    }

    @Test
    fun `folders are normalised`() {
        assertEquals("/Photos", LibrarySettings.normalizeFolder(" Photos/ "))
        assertEquals("/Photos/2024", LibrarySettings.normalizeFolder("/Photos/2024/"))
        assertEquals("/", LibrarySettings.normalizeFolder(""))
        assertEquals("/", LibrarySettings.normalizeFolder("/"))
    }
}
