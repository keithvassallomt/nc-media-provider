package com.keithvassallo.ncmediaprovider.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import org.junit.After
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
    fun `favourites flip without a listing`() {
        store.commit(listOf(inFolder(1, "/a/"), inFolder(2, "/a/").copy(isFavorite = true)), complete = true)
        assertFalse(store.applyFavorites(setOf("2")))
        assertTrue(store.applyFavorites(setOf("1", "99"))) // 99 isn't in the library and is ignored
        assertTrue(store.media("1")!!.isFavorite)
        assertFalse(store.media("2")!!.isFavorite)
        assertEquals(setOf("1", "2"), allMedia(since = 1).map(MediaItem::id).toSet())
    }

    @Test
    fun `folder etags and the root etag are saved together`() {
        store.saveFolderEtags("root-1", mapOf("/a/" to "x", "/b/" to "y"))
        assertEquals(mapOf("/a/" to "x", "/b/" to "y"), store.folderEtags())
        assertEquals("root-1", store.state().rootEtag)
        store.saveFolderEtags("root-2", mapOf("/a/" to "z"))
        assertEquals(mapOf("/a/" to "z"), store.folderEtags())
        store.resetFor("elsewhere")
        assertTrue(store.folderEtags().isEmpty())
        assertEquals("", store.state().rootEtag)
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
