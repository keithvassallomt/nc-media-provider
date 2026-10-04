package com.keithvassallo.ncmediaprovider.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** An album's photos in the keyboard (#60), paged in memory both ways. */
class KeyboardPagesTest {
    private fun taken(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun item(id: String, date: Long) = MediaItem(id, "/f/$id.jpg", "e$id", "$id.jpg", "image/jpeg", 10, date, date)

    private val album = listOf(
        item("1", taken(2024, 3, 1)),
        item("2", taken(2026, 9, 1)),
        item("3", taken(2025, 5, 1)),
        item("4", taken(2026, 9, 1)),
        item("5", taken(2023, 1, 1)),
    )

    @Test
    fun `older pages come newest first, ties broken by ID`() {
        val first = KeyboardPages.page(album, Long.MAX_VALUE, "", newer = false, limit = 3)
        assertEquals(listOf("4", "2", "3"), first.map(MediaItem::id))
        val next = KeyboardPages.page(album, first.last().dateTakenMillis, first.last().id, newer = false, limit = 3)
        assertEquals(listOf("1", "5"), next.map(MediaItem::id))
    }

    @Test
    fun `after a jump, newer pages come oldest first from the top of what is shown`() {
        val jumped = KeyboardPages.page(album, taken(2025, 1, 1), "", newer = false, limit = 10)
        assertEquals(listOf("1", "5"), jumped.map(MediaItem::id))
        val newer = KeyboardPages.page(album, jumped.first().dateTakenMillis, jumped.first().id, newer = true, limit = 2)
        assertEquals(listOf("3", "2"), newer.map(MediaItem::id))
    }

    @Test
    fun `years come newest first, once each`() {
        assertEquals(listOf(2026, 2025, 2024, 2023), KeyboardPages.years(album, ZoneOffset.UTC))
    }
}
