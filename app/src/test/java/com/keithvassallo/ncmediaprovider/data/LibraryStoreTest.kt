package com.keithvassallo.ncmediaprovider.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import org.junit.After
import com.keithvassallo.ncmediaprovider.local.LocalPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real Room store on an in-memory SQLite database (#12 and #14). A plain Application
 * keeps the app's own start-up (debug sign-in, Keystore, WorkManager) out of the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryStoreTest {
    private var now = 1_000_000L
    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = LibraryStore(database) { now }

    @After
    fun close() = database.close()

    private fun item(id: Int, etag: String = "e$id") =
        MediaItem("$id", "/f/$id.jpg", etag, "$id.jpg", "image/jpeg", 10, 1_000, 1_000)

    private fun allMedia(since: Long?, pageSize: Int = 2): List<MediaItem> {
        val rows = ArrayList<MediaItem>()
        var token: String? = null
        do {
            val page = store.mediaPage(since, token, pageSize)
            rows += page.items
            token = page.nextPageToken
        } while (token != null)
        return rows
    }

    @Test
    fun `a first commit stores everything at generation 1`() {
        assertTrue(store.commit((1..5).map(::item), complete = true))
        assertEquals(1L, store.state().generation)
        assertTrue(store.state().imported)
        val rows = allMedia(since = null)
        assertEquals((1..5).map(Int::toString).toSet(), rows.map(MediaItem::id).toSet())
        assertEquals(5, rows.size) // no duplicates across pages
        assertTrue(rows.all { it.generation == 1L })
    }

    @Test
    fun `an unchanged listing keeps the generation`() {
        store.commit((1..3).map(::item), complete = true)
        assertFalse(store.commit((1..3).map(::item), complete = true))
        assertEquals(1L, store.state().generation)
    }

    @Test
    fun `an incremental sync gets only what changed`() {
        store.commit((1..4).map(::item), complete = true)
        store.commit(listOf(item(1), item(2, etag = "edited"), item(3), item(4), item(5)), complete = true)
        assertEquals(2L, store.state().generation)
        assertEquals(setOf("2", "5"), allMedia(since = 1).map(MediaItem::id).toSet())
        assertTrue(allMedia(since = 2).isEmpty())
    }

    @Test
    fun `a complete listing journals deletions, a partial one never does`() {
        store.commit((1..4).map(::item), complete = true)
        store.commit(listOf(item(1)), complete = false)
        assertEquals(4, store.mediaCount())
        store.commit(listOf(item(1), item(2)), complete = true)
        assertEquals(2, store.mediaCount())
        assertEquals(setOf("3", "4"), store.deletedPage(1, null, 10).items.toSet())
        assertTrue(store.deletedPage(2, null, 10).items.isEmpty())
    }

    @Test
    fun `a file that comes back leaves the deletion journal`() {
        store.commit((1..2).map(::item), complete = true)
        store.commit(listOf(item(1)), complete = true)
        store.commit((1..2).map(::item), complete = true)
        assertTrue(store.deletedPage(0, null, 10).items.isEmpty())
        assertEquals(3L, store.media("2")!!.generation)
    }

    @Test
    fun `a pass is pinned to the generation it started at`() {
        store.commit((1..4).map(::item), complete = true)
        val first = store.mediaPage(null, null, 2)
        store.commit((1..6).map(::item), complete = true) // generation 2 lands mid-pass
        val rest = generateSequence(store.mediaPage(null, first.nextPageToken, 2)) { page ->
            page.nextPageToken?.let { store.mediaPage(null, it, 2) }
        }.flatMap { it.items }.toList()
        assertEquals(setOf("1", "2", "3", "4"), (first.items + rest).map(MediaItem::id).toSet())
    }

    @Test
    fun `a media token handed to the deletions query starts that pass from the beginning`() {
        store.commit((1..3).map(::item), complete = true)
        store.commit(listOf(item(1)), complete = true)
        val mediaToken = store.mediaPage(null, null, 1).nextPageToken
        assertEquals(setOf("2", "3"), store.deletedPage(0, mediaToken, 10).items.toSet())
    }

    @Test
    fun `pruned deletions put older pickers behind the floor`() {
        store.commit((1..3).map(::item), complete = true) // generation 1
        store.commit(listOf(item(1), item(2)), complete = true) // generation 2 deletes 3
        now += 1_000
        store.commit(listOf(item(1)), complete = true) // generation 3 deletes 2
        store.pruneDeletions(maxAgeMillis = 500) // drops generation 2's journal row only
        assertEquals(listOf("2"), store.deletedPage(0, null, 10).items)
        assertTrue(store.isBehindDeletionFloor(1))
        assertFalse(store.isBehindDeletionFloor(2))
    }

    private fun inFolder(id: Int, folder: String, etag: String = "e$id") =
        MediaItem("$id", "/dav$folder$id.jpg", etag, "$id.jpg", "image/jpeg", 10, 1_000, 1_000, folder = folder)

    @Test
    fun `folder commits touch only the folders that changed`() {
        // a/ holds 1 and 2, b/ holds 3, c/ holds 4
        store.commit(listOf(inFolder(1, "/a/"), inFolder(2, "/a/"), inFolder(3, "/b/"), inFolder(4, "/c/")), complete = true)
        // a/ changed: 2 was deleted and 5 added. c/ is not listed, so 4 must survive.
        assertTrue(store.commitFolders(mapOf("/a/" to listOf(inFolder(1, "/a/"), inFolder(5, "/a/"))), emptySet()))
        assertEquals(setOf("1", "3", "4", "5"), allMedia(since = null).map(MediaItem::id).toSet())
        assertEquals(listOf("2"), store.deletedPage(1, null, 10).items)
        assertEquals(setOf("5"), allMedia(since = 1).map(MediaItem::id).toSet())
    }

    @Test
    fun `a file moved between changed folders is a move, not a deletion`() {
        store.commit(listOf(inFolder(1, "/a/"), inFolder(2, "/b/")), complete = true)
        store.commitFolders(mapOf("/a/" to emptyList(), "/b/" to listOf(inFolder(2, "/b/"), inFolder(1, "/b/"))), emptySet())
        assertTrue(store.deletedPage(0, null, 10).items.isEmpty())
        assertEquals("/b/", store.media("1")!!.folder)
        assertEquals(2L, store.media("1")!!.generation)
    }

    @Test
    fun `a removed folder takes its files with it`() {
        store.commit(listOf(inFolder(1, "/a/"), inFolder(2, "/gone/"), inFolder(3, "/gone/sub/")), complete = true)
        store.commitFolders(emptyMap(), setOf("/gone/", "/gone/sub/"))
        assertEquals(setOf("1"), allMedia(since = null).map(MediaItem::id).toSet())
        assertEquals(setOf("2", "3"), store.deletedPage(0, null, 10).items.toSet())
    }

    @Test
    fun `a file at two paths keeps one row under the first path`() {
        // 1 is in /b/ and, through a second mount, also in /a/. Only /a/ changed.
        store.commit(listOf(inFolder(1, "/b/"), inFolder(2, "/c/")), complete = true)
        assertTrue(store.commitFolders(mapOf("/a/" to listOf(inFolder(1, "/a/"))), emptySet()))
        assertEquals("/a/", store.media("1")!!.folder)
        assertTrue(store.state().duplicatePaths)
        // Now /b/ changes for an unrelated reason: the row stays under /a/, and nothing is deleted.
        assertFalse(store.commitFolders(mapOf("/b/" to listOf(inFolder(1, "/b/"))), emptySet()))
        assertEquals("/a/", store.media("1")!!.folder)
        assertTrue(store.deletedPage(0, null, 10).items.isEmpty())
    }

    @Test
    fun `a file listed in two changed folders keeps one row under the first path`() {
        store.commitFolders(mapOf("/b/" to listOf(inFolder(1, "/b/")), "/a/" to listOf(inFolder(1, "/a/"))), emptySet())
        assertEquals(1, store.mediaCount())
        assertEquals("/a/", store.media("1")!!.folder)
        assertTrue(store.state().duplicatePaths)
    }

    @Test
    fun `a new or lost phone copy moves the row to the next generation`() {
        store.commit((1..3).map(::item), complete = true)
        val phone = "content://media/external/images/media/7"
        assertEquals(1, store.applyLocalMatches(mapOf("2" to phone)))
        assertEquals(phone, store.media("2")!!.mediaStoreUri)
        assertEquals(listOf("2"), allMedia(since = 1).map(MediaItem::id))
        assertEquals(0, store.applyLocalMatches(mapOf("2" to phone)))
        assertEquals(2L, store.state().generation)
        assertEquals(1, store.applyLocalMatches(emptyMap()))
        assertEquals(null, store.media("2")!!.mediaStoreUri)
        assertEquals(3L, store.media("2")!!.generation)
    }

    @Test
    fun `listings keep the phone copy, changed or not`() {
        store.commit((1..2).map(::item), complete = true)
        val phone = "content://media/external/images/media/7"
        store.applyLocalMatches(mapOf("1" to phone, "2" to phone.replace('7', '8')))
        assertFalse(store.commit((1..2).map(::item), complete = true))
        assertTrue(store.commit(listOf(item(1, etag = "changed"), item(2)), complete = true))
        assertEquals(phone, store.media("1")!!.mediaStoreUri)
        assertEquals(phone, store.matchCandidates(emptyList()).single { it.id == "1" }.mediaStoreUri)
    }

    @Test
    fun `match candidates are rows with a phone item's size or name, and matched rows`() {
        store.commit(
            listOf(
                item(1).copy(fileName = "PXL_1.JPG", sizeBytes = 500),
                item(2).copy(fileName = "other.jpg", sizeBytes = 700),
                item(3).copy(fileName = "renamed.jpg", sizeBytes = 900),
                item(4).copy(fileName = "unrelated.jpg", sizeBytes = 999),
            ),
            complete = true,
        )
        store.applyLocalMatches(mapOf("2" to "content://media/external/images/media/5"))
        fun photo(name: String, size: Long) = LocalPhoto(1, "content://media/external/images/media/1", name, size, 0, "image/jpeg")
        val candidates = store.matchCandidates(listOf(photo("pxl_1.jpg", 1), photo("x.jpg", 900)))
        assertEquals(listOf("1", "2", "3"), candidates.map { it.id }.sorted())
    }

    @Test
    fun `favourites flip without a listing`() {
        store.commit(listOf(inFolder(1, "/a/"), inFolder(2, "/a/").copy(isFavorite = true)), complete = true)
        assertFalse(store.applyFavorites(setOf("2")))
        assertTrue(store.applyFavorites(setOf("1", "99"))) // 99 isn't in the library and is ignored
        assertTrue(store.media("1")!!.isFavorite)
        assertFalse(store.media("2")!!.isFavorite)
        assertEquals(setOf("1", "2"), allMedia(since = 1).map(MediaItem::id).toSet())
    }

    @Test
    fun `folder state is saved together, and a change check keeps what only a full listing knows`() {
        store.saveFolderState("root-1", mapOf("/a/" to "x", "/b/" to "y"), setOf("/b/", "/a/x/"), respectsNoMedia = true, duplicatePaths = true)
        assertEquals(mapOf("/a/" to "x", "/b/" to "y"), store.folderEtags())
        assertEquals("root-1", store.state().rootEtag)
        assertEquals(setOf("/b/", "/a/x/"), store.hiddenFolders())
        store.saveFolderState("root-2", mapOf("/a/" to "z"), emptySet())
        assertEquals(mapOf("/a/" to "z"), store.folderEtags())
        assertTrue(store.hiddenFolders().isEmpty())
        assertTrue(store.state().respectsNoMedia)
        assertTrue(store.state().duplicatePaths)
        store.resetFor("elsewhere")
        assertTrue(store.folderEtags().isEmpty())
        assertEquals("", store.state().rootEtag)
        assertFalse(store.state().duplicatePaths)
    }

    @Test
    fun `finishing a sync records the time`() {
        assertEquals(0L, store.state().lastCheckMillis)
        store.markChecked()
        assertEquals(now, store.state().lastCheckMillis)
    }

    private fun livePair(n: Int) = listOf(
        MediaItem("${n}0", "/dav/Photos/IMG_$n.HEIC", "p$n", "IMG_$n.HEIC", "image/heic", 10, 1_000, 1_000, folder = "/Photos/"),
        MediaItem("${n}1", "/dav/Photos/IMG_$n.MOV", "v$n", "IMG_$n.MOV", "video/quicktime", 10, 1_000, 1_000, folder = "/Photos/"),
    )

    /** Memories' view of a pair: the photo with a live-photo ID, the video left out of its timeline. */
    private fun indexedPhoto(n: Int, date: Long = 2_000L) = MemoriesFile("${n}0", "p$n", 7, date, 4032, 3024, "live-$n", isVideo = false)

    @Test
    fun `Memories values replace core ones, and switching it off puts them back`() {
        store.commit(livePair(1), complete = true)
        assertTrue(store.applyMemories(mapOf(7 to 1), readDays = setOf(7), files = listOf(indexedPhoto(1))))
        val photo = store.media("10")!!
        assertEquals(2_000L to 4032, photo.dateTakenMillis to photo.width)
        assertEquals(1_000L, photo.listedDateMillis)
        assertEquals(2L, photo.generation)
        assertEquals(1 to 1, store.memoriesStats())
        // Reading the same again changes nothing.
        assertFalse(store.applyMemories(mapOf(7 to 1), readDays = setOf(7), files = listOf(indexedPhoto(1))))

        assertTrue(store.clearMemories())
        val restored = store.media("10")!!
        assertEquals(1_000L to 0, restored.dateTakenMillis to restored.width)
        assertFalse(store.clearMemories())
    }

    @Test
    fun `a live photo's video is reported as deleted, and comes back when the pairing goes`() {
        store.commit(livePair(1) + livePair(2), complete = true)
        store.applyMemories(mapOf(7 to 2), readDays = setOf(7), files = listOf(indexedPhoto(1), indexedPhoto(2)))
        assertEquals(setOf("10", "20"), allMedia(since = null).map(MediaItem::id).toSet())
        assertEquals(setOf("11", "21"), store.deletedPage(1, null, 10).items.toSet())
        assertEquals(2, store.mediaCount())

        // A later listing keeps them hidden, without another generation.
        val generation = store.state().generation
        assertFalse(store.commit(livePair(1) + livePair(2), complete = true))
        assertEquals(generation, store.state().generation)

        // Memories no longer pairs the second photo (re-read of its day): its video is back.
        assertTrue(store.applyMemories(mapOf(7 to 2), readDays = setOf(7), files = listOf(indexedPhoto(1), indexedPhoto(2).copy(liveId = null))))
        assertEquals(setOf("21"), allMedia(since = generation).map(MediaItem::id).toSet())
        assertTrue(store.deletedPage(generation, null, 10).items.isEmpty())
        assertEquals(listOf("11"), store.deletedPage(0, null, 10).items)
    }

    @Test
    fun `a partial read replaces only the days read, and drops days that are gone`() {
        store.commit(livePair(1) + livePair(2), complete = true)
        store.applyMemories(mapOf(7 to 1, 8 to 1), readDays = setOf(7, 8), files = listOf(indexedPhoto(1), indexedPhoto(2).copy(dayId = 8)))
        assertEquals(2, store.memoriesStats().first)

        store.applyMemories(mapOf(8 to 1), readDays = emptySet(), files = emptyList())
        assertEquals(1, store.memoriesStats().first)
        assertEquals(1_000L, store.media("10")!!.dateTakenMillis)
        assertEquals(2_000L, store.media("20")!!.dateTakenMillis)
        assertEquals(mapOf(8 to 1), store.memoriesDays())
    }

    @Test
    fun `a read cut short stores the days it read, and leaves the rest to be read`() {
        store.commit(livePair(1) + livePair(2), complete = true)
        val days = mapOf(7 to 1, 8 to 1)
        assertTrue(store.applyMemories(days, readDays = setOf(7), files = listOf(indexedPhoto(1))))
        assertEquals(mapOf(7 to 1), store.memoriesDays())
        assertEquals(setOf(8), MemoriesApi.changedDays(days, store.memoriesDays(), store.staleMemoriesDays()))
        assertEquals(2_000L to 1_000L, store.media("10")!!.dateTakenMillis to store.media("20")!!.dateTakenMillis)
    }

    @Test
    fun `an edited file no longer takes Memories' values, and its day reads as stale`() {
        store.commit(livePair(1), complete = true)
        store.applyMemories(mapOf(7 to 1), readDays = setOf(7), files = listOf(indexedPhoto(1)))
        val edited = livePair(1).map { if (it.id == "10") it.copy(etag = "p1-edited") else it }
        assertTrue(store.commit(edited, complete = true))
        assertEquals(1_000L, store.media("10")!!.dateTakenMillis)
        assertEquals(listOf(7), store.staleMemoriesDays())
    }

    @Test
    fun `video headers fill in what's missing and resolve the date`() {
        store.commit(livePair(1), complete = true)
        assertEquals(listOf("11"), store.videosWithoutHeader(10).map(MediaItem::id))
        assertTrue(store.applyVideoHeaders(mapOf("11" to VideoHeader.Info(2_500L, 3_000L))))
        val video = store.media("11")!!
        assertEquals(Triple(2_500L, 3_000L, 3_000L), Triple(video.durationMillis, video.recordedMillis, video.dateTakenMillis))
        assertTrue(store.videosWithoutHeader(10).isEmpty())

        store.commit(livePair(1).map { if (it.id == "11") it.copy(etag = "v1-edited") else it }, complete = true)
        assertTrue(store.applyVideoHeaders(mapOf("11" to null)))
        val unreadable = store.media("11")!!
        assertEquals(MediaItem.NOT_IN_HEADER to MediaItem.NOT_IN_HEADER, unreadable.durationMillis to unreadable.recordedMillis)
        assertEquals(1_000L, unreadable.dateTakenMillis)
    }

    private fun albumItem(albumId: String, id: Int, date: Long = 5_000L) = AlbumItem(
        albumId, "$id", "/remote.php/dav/photos/alice/albums/$albumId/$id-$id.jpg", "e$id", "$id.jpg", "image/jpeg", 10, date, date, 0, 0, false,
    )

    @Test
    fun `albums show library rows and album-only files, and only albums with something to show`() {
        store.commit((1..3).map(::item), complete = true)
        val trip = Album("nc-album-trip", "/remote.php/dav/photos/alice/albums/Trip/", "Trip", false, coverId = "9", signature = "s1")
        val empty = Album("nc-album-empty", "/remote.php/dav/photos/alice/albums/Empty/", "Empty", false, coverId = null, signature = "s0")
        assertTrue(store.saveAlbums(listOf(trip, empty), mapOf(trip.id to listOf(albumItem(trip.id, 1), albumItem(trip.id, 9)), empty.id to emptyList())))

        val shown = store.pickerAlbums().single()
        assertEquals(Triple("Trip", 2, "9"), Triple(shown.name, shown.count, shown.coverId))
        val page = store.albumPage(trip.id, null, 1)
        assertEquals(listOf("1"), page.items.map(MediaItem::id))
        assertEquals("/f/1.jpg", page.items.single().href)
        val rest = store.albumPage(trip.id, page.nextPageToken, 1)
        assertEquals(listOf("9"), rest.items.map(MediaItem::id))
        assertEquals(albumItem(trip.id, 9).href, store.albumOnlyItem("9")!!.href)
        assertNull(store.albumOnlyItem("2"))

        // The same listing again changes nothing; a removed album takes its files.
        assertFalse(store.saveAlbums(listOf(trip, empty), mapOf(trip.id to listOf(albumItem(trip.id, 1), albumItem(trip.id, 9)))))
        assertTrue(store.saveAlbums(listOf(empty), emptyMap()))
        assertTrue(store.pickerAlbums().isEmpty())
        assertNull(store.albumOnlyItem("9"))
        assertTrue(store.clearAlbums())
        assertTrue(store.albums().isEmpty())
        assertFalse(store.clearAlbums())
    }

    @Test
    fun `a file only an album holds joins the library once, and folder listings leave it alone`() {
        store.commit((1..3).map { inFolder(it, "/a/") }, complete = true)
        val trip = Album("nc-album-trip", "/remote.php/dav/photos/alice/albums/Trip/", "Trip", false, coverId = null, signature = "s1")
        val other = Album("nc-album-other", "/remote.php/dav/photos/alice/sharedalbums/Other (bob)/", "Other (bob)", true, coverId = null, signature = "s1")
        store.saveAlbums(
            listOf(trip, other),
            mapOf(trip.id to listOf(albumItem(trip.id, 1), albumItem(trip.id, 9)), other.id to listOf(albumItem(other.id, 9))),
        )
        assertTrue(store.applyAlbumRows())
        assertEquals(setOf("1", "2", "3", "9"), allMedia(since = null).map(MediaItem::id).toSet())
        assertTrue(Albums.isAlbumPath(store.media("9")!!.href))
        assertEquals("/dav/a/1.jpg", store.media("1")!!.href)
        assertFalse(store.applyAlbumRows())

        // A full listing of the folders doesn't delete it; a folder listing that finds it there takes it over.
        assertFalse(store.commit((1..3).map { inFolder(it, "/a/") }, complete = true))
        assertTrue(store.commitFolders(mapOf("/b/" to listOf(inFolder(9, "/b/"))), emptySet()))
        assertEquals("/dav/b/9.jpg", store.media("9")!!.href)
        assertFalse(store.state().duplicatePaths)
        assertFalse(store.applyAlbumRows())

        // Gone from the folders but still in an album: back under the album path.
        store.commit((1..3).map { inFolder(it, "/a/") }, complete = true)
        assertTrue(store.applyAlbumRows())
        assertTrue(Albums.isAlbumPath(store.media("9")!!.href))
        assertTrue(store.deletedPage(store.state().generation - 1, null, 10).items.isEmpty())

        // Out of every album: out of the library.
        store.saveAlbums(listOf(trip, other), mapOf(trip.id to listOf(albumItem(trip.id, 1)), other.id to emptyList()))
        assertTrue(store.applyAlbumRows())
        assertNull(store.media("9"))
        assertEquals(listOf("9"), store.deletedPage(store.state().generation - 1, null, 10).items)
    }

    @Test
    fun `people show only their photos in the library, a page at a time`() {
        store.commit((1..4).map(::item), complete = true)
        val alex = Person("nc-person-1", "keith/1", "Alex", "3|e")
        val nobody = Person("nc-person-2", "keith/2", "", "1|e")
        assertTrue(store.savePeople(listOf(alex, nobody), mapOf(alex.id to listOf("1", "2", "9"), nobody.id to listOf("9"))))
        assertEquals(listOf("Alex"), store.pickerPeople().map(PickerPerson::name))
        assertEquals(2, store.pickerPeople().single().count)
        val first = store.personPage(alex.id, null, 1)
        assertEquals(listOf("1"), first.items.map(MediaItem::id))
        assertEquals(listOf("2"), store.personPage(alex.id, first.nextPageToken, 1).items.map(MediaItem::id))

        assertTrue(store.savePeople(listOf(nobody), emptyMap()))
        assertTrue(store.pickerPeople().isEmpty())
        assertTrue(store.clearPeople())
        assertTrue(store.persons().isEmpty())
    }

    @Test
    fun `state survives a new store on the same database, and a reset starts over`() {
        store.commit((1..3).map(::item), complete = true)
        val reopened = LibraryStore(database) { now }
        assertEquals(store.state().instanceId, reopened.state().instanceId)
        assertEquals(1L, reopened.state().generation)

        val before = reopened.state().instanceId
        reopened.resetFor("other-source")
        assertNotEquals(before, reopened.state().instanceId)
        assertEquals(0, reopened.mediaCount())
        assertEquals(0L, reopened.state().generation)
        assertNull(reopened.media("1"))
    }
}
