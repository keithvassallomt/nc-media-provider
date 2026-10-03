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

    private fun video(etag: String = "v1", listedDate: Long = 5_000L) = MediaItem(
        "9", "/f/9.mov", etag, "9.mov", "video/quicktime", 10, 1_000, listedDate, width = 0, height = 0,
    )

    private fun memories(etag: String = "v1", date: Long = 3_000L) = MemoriesFile("9", etag, 1, date, 1920, 1080, null, isVideo = true)

    @Test
    fun `Memories wins over the header, the header over the listing`() {
        val listed = video()
        assertEquals(5_000L, listed.resolved(null).dateTakenMillis)
        val read = listed.copy(recordedMillis = 4_000L)
        assertEquals(4_000L, read.resolved(null).dateTakenMillis)
        val enriched = read.resolved(memories())
        assertEquals(3_000L, enriched.dateTakenMillis)
        assertEquals(1920 to 1080, enriched.width to enriched.height)
        // Memories indexed an older version of the file: its values don't apply.
        assertEquals(4_000L, read.resolved(memories(etag = "v0")).dateTakenMillis)
        // A header without a recording time falls back to the listing.
        assertEquals(5_000L, listed.copy(recordedMillis = MediaItem.NOT_IN_HEADER).resolved(null).dateTakenMillis)
        // Switching Memories off puts the listing's values back.
        assertEquals(read.resolved(null), enriched.resolved(null))
    }

    @Test
    fun `header values survive a listing of the same file, and go with an edit`() {
        val stored = video().copy(durationMillis = 2_000L, recordedMillis = 4_000L).resolved(null)
        assertTrue(diffLibrary(listOf(stored), listOf(video()), complete = true).isEmpty)

        val edited = diffLibrary(listOf(stored), listOf(video(etag = "v2")), complete = true).upserts.single()
        assertEquals(0L to 0L, edited.durationMillis to edited.recordedMillis)
        assertEquals(5_000L, edited.dateTakenMillis)
    }

    @Test
    fun `listed rows are resolved against Memories`() {
        val changes = diffLibrary(emptyList(), listOf(video()), complete = true) { id -> memories().takeIf { id == "9" } }
        val row = changes.upserts.single()
        assertEquals(3_000L, row.dateTakenMillis)
        assertEquals(5_000L, row.listedDateMillis)
        // A later listing of the same file changes nothing.
        assertTrue(diffLibrary(listOf(row), listOf(video()), complete = true) { memories() }.isEmpty)
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
