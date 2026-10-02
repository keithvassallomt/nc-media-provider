package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryChangesTest {
    private fun item(id: String, etag: String = "e$id", generation: Long = 0L) =
        MediaItem(id, "/f/$id.jpg", etag, "$id.jpg", "image/jpeg", 10, 1_000, 1_000, generation = generation)

    @Test
    fun `unchanged rows are not upserts, whatever their stored generation`() {
        val stored = listOf(item("1", generation = 4), item("2", generation = 7))
        assertTrue(diffLibrary(stored, listOf(item("1"), item("2")), complete = true).isEmpty)
    }

    @Test
    fun `new and changed rows are upserts`() {
        val changes = diffLibrary(listOf(item("1"), item("2")), listOf(item("1", etag = "edited"), item("2"), item("3")), complete = true)
        assertEquals(listOf("1", "3"), changes.upserts.map(MediaItem::id))
    }

    @Test
    fun `only a complete listing deletes`() {
        val stored = listOf(item("1"), item("2"))
        assertEquals(listOf("2"), diffLibrary(stored, listOf(item("1")), complete = true).deletedIds)
        assertTrue(diffLibrary(stored, listOf(item("1")), complete = false).deletedIds.isEmpty())
    }

    @Test
    fun `page tokens round-trip and belong to one pass`() {
        val token = PageToken(PageToken.MEDIA, top = 9, afterGeneration = 3, afterId = "42")
        assertEquals(token, PageToken.decode(token.encode(), PageToken.MEDIA))
        // Android 17 may pass the media token into the deletions query.
        assertNull(PageToken.decode(token.encode(), PageToken.DELETED))
        assertNull(PageToken.decode("500", PageToken.MEDIA)) // an old offset token
        assertNull(PageToken.decode(null, PageToken.MEDIA))
    }
}
