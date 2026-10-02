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
 * Runs the real Room store on an in-memory SQLite database (PLAN 2.1 and 2.3). A plain Application
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
