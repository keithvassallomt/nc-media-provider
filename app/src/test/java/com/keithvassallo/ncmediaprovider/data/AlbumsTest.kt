package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nextcloud Photos albums (#43), against responses saved from the test servers: alice's own
 * album "Holiday", and "Road trip", which bob shares with her. One of its photos is in bob's
 * folder shared with alice, the other only in the album.
 */
class AlbumsTest {
    private fun fixture(server: String, name: String) = File("../testdata/nextcloud/$server/$name").inputStream()

    @Test
    fun `own and shared albums are read, without their collection`() {
        for (server in listOf("33-full", "35-full")) {
            val own = MultistatusParser.parseAlbums(fixture(server, "photos-albums.xml"), isShared = false).single()
            assertEquals("Holiday", own.name)
            assertEquals(2, own.itemCount)
            assertEquals("107", own.lastPhotoId)
            assertFalse(own.isShared)

            val shared = MultistatusParser.parseAlbums(fixture(server, "photos-sharedalbums.xml"), isShared = true).single()
            assertEquals("Road trip (bob)", shared.name)
            assertEquals("/remote.php/dav/photos/alice/sharedalbums/Road%20trip%20(bob)/", shared.href)
            assertEquals(2, shared.itemCount)
            assertTrue(shared.isShared)
        }
    }

    @Test
    fun `an album's files are stored under their own names, with paths inside the album`() {
        val album = Albums.albumId("/remote.php/dav/photos/alice/sharedalbums/Road%20trip%20(bob)/")
        val items = MultistatusParser.parse(fixture("35-full", "photos-sharedalbums-0.xml")).map { Albums.item(album, it) }
        assertEquals(listOf("90" to "trip-1.jpg", "122" to "bob-only.jpg"), items.map { it.id to it.fileName })
        assertTrue(items.all { Albums.isAlbumPath(it.href) && it.albumId == album })
        // No date in the EXIF: the upload's modification time, as for library files.
        assertEquals(1_700_000_000_000L, items[1].dateTakenMillis)
        assertFalse(Albums.isAlbumPath("/remote.php/dav/files/alice/Photos/a.jpg"))
    }

    @Test
    fun `album IDs are stable, prefixed, and the same however the path is encoded`() {
        val id = Albums.albumId("/remote.php/dav/photos/alice/albums/Holiday/")
        assertEquals(id, Albums.albumId("/remote.php/dav/photos/alice/albums/Holiday"))
        assertTrue(Albums.isAlbumId(id))
        assertTrue(id.startsWith("nc-album-") && id.length == "nc-album-".length + 16)
        assertEquals(
            Albums.albumId("/remote.php/dav/photos/alice/sharedalbums/Road%20trip%20(bob)/"),
            Albums.albumId("/remote.php/dav/photos/alice/sharedalbums/Road trip (bob)/"),
        )
        assertNotEquals(id, Albums.albumId("/remote.php/dav/photos/alice/sharedalbums/Holiday (bob)/"))
    }

    private fun item(id: String, date: Long = 1_000L) = AlbumItem("a", id, "/remote.php/dav/photos/u/albums/a/$id-$id.jpg", "e$id", "$id.jpg", "image/jpeg", 10, date, date, 0, 0, false)

    private fun row(id: String, date: Long, liveVideo: Boolean = false) =
        MediaItem(id, "/f/$id.jpg", "e$id", "$id.jpg", "image/jpeg", 10, 1_000, date, generation = 3, isLiveVideo = liveVideo)

    @Test
    fun `library rows stand in for their files, and hidden live-photo videos stay hidden`() {
        val contents = Albums.contents(
            listOf(item("1"), item("2"), item("3")),
            mapOf("1" to row("1", 5_000L), "3" to row("3", 1_000L, liveVideo = true)),
            generation = 9,
        )
        assertEquals(listOf("1", "2"), contents.map(MediaItem::id))
        // Memories' date, say, from the library row.
        assertEquals(5_000L, contents[0].dateTakenMillis)
        assertEquals(9L, contents[1].generation)
        assertTrue(Albums.isAlbumPath(contents[1].href))
    }

    @Test
    fun `the picker gets Photos' cover when it can show it, else the newest`() {
        val album = Album("nc-album-1", "/h/", "Trip", false, coverId = "2", signature = "")
        val contents = listOf(row("1", 1_000L), row("2", 2_000L), row("3", 3_000L))
        val shown = Albums.forPicker(album, contents)!!
        assertEquals(Triple("2", 3, 3_000L), Triple(shown.coverId, shown.count, shown.dateTakenMillis))
        assertEquals("3", Albums.forPicker(album.copy(coverId = "9"), contents)!!.coverId)
        assertEquals("3", Albums.forPicker(album.copy(coverId = null), contents)!!.coverId)
        assertNull(Albums.forPicker(album, emptyList()))
    }
}
