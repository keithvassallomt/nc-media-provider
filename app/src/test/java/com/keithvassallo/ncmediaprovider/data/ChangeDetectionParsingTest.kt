package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The PLAN 2.4 requests, parsed from real responses saved from the Nextcloud 35 test server. */
class ChangeDetectionParsingTest {
    private fun fixture(name: String) = File("../testdata/nextcloud/35-full/$name").inputStream()

    @Test
    fun `the root folder's own entry carries its etag`() {
        val root = fixture("propfind-root-depth0.xml").use(MultistatusParser::parseEntries).single()
        assertEquals("/remote.php/dav/files/alice/Photos/", root.href)
        assertEquals("6abfb402ad5e2", root.etag)
        assertTrue(root.isFolder)
    }

    @Test
    fun `one search returns every folder below the root with its etag`() {
        val folders = fixture("search-folders.xml").use(MultistatusParser::parseEntries)
        assertEquals(5, folders.size)
        assertTrue(folders.all { it.isFolder && it.etag.isNotEmpty() })
        assertEquals("6abfb4004ee08", folders.single { it.href.endsWith("/2021/Summer/") }.etag)
    }

    @Test
    fun `a one-level listing yields the files and skips the folders`() {
        val files = fixture("propfind-folder-depth1.xml").use(MultistatusParser::parse)
        assertEquals(6, files.size) // the folder itself and its subfolder are skipped
        assertTrue(files.none { it.href.endsWith("/") })
        assertTrue(files.any { it.fileName == "Ünïcødé & spaces #1.jpg" })
    }

    @Test
    fun `favourites come back as file IDs`() {
        val ids = fixture("search-favorites.xml").use(MultistatusParser::parseEntries).mapNotNull(DavEntry::fileId).toSet()
        assertEquals(setOf("102", "107"), ids)
    }

    @Test
    fun `a folder is inside itself and its ancestors, not a sibling with a longer name`() {
        val hidden = setOf("/dav/alice/Photos/Private/")
        assertTrue(isInsideAny("/dav/alice/Photos/Private/", hidden))
        assertTrue(isInsideAny("/dav/alice/Photos/Private/2024/", hidden))
        assertFalse(isInsideAny("/dav/alice/Photos/Private stuff/", hidden))
        assertFalse(isInsideAny("/dav/alice/Photos/", hidden))
    }

    @Test
    fun `folders and files meet on decoded keys`() {
        val folder = "/remote.php/dav/files/alice/Photos/2023/"
        assertEquals(folder, folderKey(folder))
        assertEquals(folder, folderKey(folder.trimEnd('/')))
        assertEquals(folder, parentFolderKey("/remote.php/dav/files/alice/Photos/2023/%c3%9cn%c3%afc%c3%b8d%c3%a9%20%26%20spaces%20%231.jpg"))
        assertEquals("/remote.php/dav/files/alice/Shared trip/", folderKey("/remote.php/dav/files/alice/Shared%20trip/"))
        assertEquals("/remote.php/dav/files/alice/Shared trip/", parentFolderKey("/remote.php/dav/files/alice/Shared%20trip/trip-1.jpg"))
    }

    @Test
    fun `change detection requests ask for the right things`() {
        val folders = SearchRequest.folders("alice", listOf("/Photos"))
        assertTrue(folders.contains("<d:literal>httpd/unix-directory</d:literal>"))
        assertTrue(folders.contains("<d:href>/files/alice/Photos</d:href>"))
        val favorites = SearchRequest.favorites("alice", listOf("/Photos"), listOf("image/"))
        assertTrue(favorites.contains("<d:eq><d:prop><oc:favorite/></d:prop><d:literal>1</d:literal></d:eq>"))
        assertTrue(PropfindRequest.FILES.contains("<d:resourcetype/>"))
    }
}
