package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ListingTest {
    private fun file(id: Int, second: Long, href: String = "/f/$id.jpg") =
        RemoteFile(href, "$id", "e$id", "image/jpeg", 1, second * 1000, null, 0, 0, false, false)

    /** A fake server that filters, orders newest first and limits the way Nextcloud does. */
    private class FakeServer(private val files: List<RemoteFile>) {
        var requests = 0

        fun search(filter: ModifiedFilter?, limit: Int): List<RemoteFile> {
            requests++
            return files.filter { file ->
                val second = file.lastModifiedMillis / 1000
                when (filter) {
                    null -> true
                    is ModifiedFilter.AtOrBefore -> second <= filter.seconds
                    is ModifiedFilter.Exactly -> second == filter.seconds
                }
            }.sortedByDescending(RemoteFile::lastModifiedMillis).take(limit)
        }
    }

    private fun assertListsAll(files: List<RemoteFile>, pageSize: Int) {
        val listed = listByModifiedWindows(pageSize, search = FakeServer(files)::search).files
        assertEquals(files.size, listed.size)
        assertEquals(files.map(RemoteFile::fileId).toSet(), listed.map(RemoteFile::fileId).toSet())
    }

    @Test
    fun `lists every file when many share one second`() {
        // The shape Phase 1.5 hit on a real server: one second holding far more than a page.
        val files = (1..2).map { file(it, 900) } + (3..12).map { file(it, 800) } + (13..14).map { file(it, 700) }
        assertListsAll(files, pageSize = 3)
    }

    @Test
    fun `lists every file when a whole page and more share the newest second`() {
        assertListsAll((1..9).map { file(it, 500) } + listOf(file(10, 400)), pageSize = 4)
    }

    @Test
    fun `lists every file when pages end exactly on second boundaries`() {
        assertListsAll((1..12).map { file(it, 1000L - (it - 1) / 3) }, pageSize = 3)
    }

    @Test
    fun `a short first page needs one request`() {
        val server = FakeServer((1..5).map { file(it, 100L + it) })
        assertEquals(5, listByModifiedWindows(10, search = server::search).files.size)
        assertEquals(1, server.requests)
    }

    @Test
    fun `a file at two paths is listed once, under the first path, whichever comes first`() {
        val files = listOf(file(1, 300, "/f/z/1.jpg"), file(2, 200), file(1, 300, "/f/a/1.jpg"), file(3, 100))
        for (order in listOf(files, files.reversed())) {
            val batches = mutableListOf<RemoteFile>()
            val listing = listByModifiedWindows(2, onBatch = { batches += it }, search = FakeServer(order)::search)
            assertEquals(listOf("1", "2", "3"), listing.files.map(RemoteFile::fileId).sorted())
            assertEquals("/f/a/1.jpg", listing.files.single { it.fileId == "1" }.href)
            assertEquals(1, listing.duplicates)
            // A first import commits batches as they come: the winning path must reach it last.
            assertEquals("/f/a/1.jpg", batches.last { it.fileId == "1" }.href)
        }
    }

    @Test
    fun `files without a modification time do not loop forever`() {
        assertListsAll((1..5).map { file(it, 0) }, pageSize = 2)
    }
}
