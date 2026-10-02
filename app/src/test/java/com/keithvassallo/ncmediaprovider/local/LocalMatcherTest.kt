package com.keithvassallo.ncmediaprovider.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMatcherTest {
    private val hour = 3_600_000L
    private val taken = 1_790_000_000_000L

    private fun cloud(id: String, name: String, size: Long, date: Long = taken, mime: String = "image/jpeg", uri: String? = null) =
        CloudPhoto(id, name, size, date, mime, uri)

    private fun local(id: Long, name: String, size: Long, date: Long = taken, mime: String = "image/jpeg") =
        LocalPhoto(id, "content://media/external/images/media/$id", name, size, date, mime)

    private fun uri(id: Long) = "content://media/external/images/media/$id"

    @Test
    fun `an auto-upload matches on name and size, an hour apart as on Keith's phone`() {
        // Nextcloud's date is whole seconds and in another zone: 3,599.4 s apart in practice.
        val matches = LocalMatcher.match(
            listOf(cloud("1", "PXL_20260920_101500123.jpg", 4_000_000, date = taken + hour)),
            listOf(local(7, "pxl_20260920_101500123.JPG", 4_000_000, date = taken + 600)),
        )
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `a phone item goes to one row only`() {
        // The same photo uploaded to two folders: MediaProvider refuses two rows naming one local item.
        val matches = LocalMatcher.match(
            listOf(cloud("2", "a.jpg", 100), cloud("1", "a.jpg", 100)),
            listOf(local(7, "a.jpg", 100)),
        )
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `two copies on each side pair up`() {
        val matches = LocalMatcher.match(
            listOf(cloud("1", "a.jpg", 100), cloud("2", "a.jpg", 100)),
            listOf(local(8, "a.jpg", 100), local(7, "a.jpg", 100)),
        )
        assertEquals(mapOf("1" to uri(7), "2" to uri(8)), matches)
    }

    @Test
    fun `a renamed upload matches on size and date`() {
        val matches = LocalMatcher.match(
            listOf(cloud("1", "2026-09-20 10.15.00.jpg", 4_000_123, date = taken - 2 * hour)),
            listOf(local(7, "PXL_20260920_101500123.jpg", 4_000_123), local(8, "other.jpg", 4_000_123, date = taken - 5 * 60_000)),
        )
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `an edited copy matches on name and date, but only of the same kind`() {
        val matches = LocalMatcher.match(
            listOf(cloud("1", "IMG_1.jpg", 100), cloud("2", "IMG_2.mp4", 900, mime = "video/mp4")),
            listOf(local(7, "IMG_1.jpg", 250), local(8, "IMG_2.mp4", 400, mime = "image/jpeg")),
        )
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `without a date only name and size can match`() {
        // Screenshots have no date taken in MediaStore.
        val matches = LocalMatcher.match(
            listOf(cloud("1", "Screenshot_1.png", 500, mime = "image/png"), cloud("2", "renamed.png", 600, mime = "image/png")),
            listOf(local(7, "Screenshot_1.png", 500, date = 0, mime = "image/png"), local(8, "Screenshot_2.png", 600, date = 0, mime = "image/png")),
        )
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `an existing match is kept while it still fits, even against a stronger newcomer`() {
        // Row 1 was matched by size and date to 7. A same-name copy (8) appears later.
        val rows = listOf(cloud("1", "a.jpg", 100, uri = uri(7)))
        val matches = LocalMatcher.match(rows, listOf(local(7, "renamed.jpg", 100), local(8, "a.jpg", 100)))
        assertEquals(mapOf("1" to uri(7)), matches)
    }

    @Test
    fun `a match whose phone copy is gone or no longer fits is redone`() {
        val gone = LocalMatcher.match(listOf(cloud("1", "a.jpg", 100, uri = uri(9))), listOf(local(8, "a.jpg", 100)))
        assertEquals(mapOf("1" to uri(8)), gone)
        val changed = LocalMatcher.match(listOf(cloud("1", "a.jpg", 100, uri = uri(7))), listOf(local(7, "b.jpg", 999, date = 0)))
        assertTrue(changed.isEmpty())
    }

    @Test
    fun `names compare as SQLite's lower() does`() {
        assertEquals("pxl_1.jpg", LocalMatcher.nameKey("PXL_1.JPG"))
        assertEquals("Ünïcødé.jpg", LocalMatcher.nameKey("Ünïcødé.JPG"))
    }

    @Test
    fun `dates match across whole time-zone offsets only`() {
        assertTrue(LocalMatcher.sameMoment(taken, taken + 1_999))
        assertTrue(LocalMatcher.sameMoment(taken, taken - hour - 1_500))
        assertTrue(LocalMatcher.sameMoment(taken, taken + 5 * hour + 45 * 60_000)) // UTC+5:45
        assertTrue(LocalMatcher.sameMoment(taken, taken - 14 * hour))
        assertFalse(LocalMatcher.sameMoment(taken, taken + 3_000))
        assertFalse(LocalMatcher.sameMoment(taken, taken + 10 * 60_000))
        assertFalse(LocalMatcher.sameMoment(taken, taken + 15 * hour))
        assertFalse(LocalMatcher.sameMoment(taken, 0))
    }
}
