package com.keithvassallo.ncmediaprovider.data

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs against a live server, normally a test server from tools/testserver/up.sh. Skipped unless
 * NC_TEST_URL is set, e.g.
 *   NC_TEST_URL=http://127.0.0.1:8135 NC_TEST_LOGIN=alice NC_TEST_PASSWORD=throwaway-35-full \
 *     ./gradlew testDebugUnitTest --tests '*IntegrationTest'
 */
class NextcloudClientIntegrationTest {
    private val client = NextcloudClient()
    private lateinit var account: NextcloudAccount
    private val folder = System.getenv("NC_TEST_FOLDER") ?: "/Photos"
    private val folders = listOf(folder)
    private val http = OkHttpClient()
    private val jpeg by lazy { File("../tools/testserver/library/alice/Photos/2021/Summer/no-exif.jpg").readBytes() }

    @Before
    fun requireServer() {
        val url = System.getenv("NC_TEST_URL")
        assumeTrue("NC_TEST_URL not set", url != null)
        val login = System.getenv("NC_TEST_LOGIN") ?: "alice"
        account = NextcloudAccount(url!!, login, login, System.getenv("NC_TEST_PASSWORD").orEmpty())
    }

    @Test
    fun `date-window pages add up to the same listing as one big page`() {
        val whole = client.listMedia(account, folders, "image/", pageSize = 1000).files
        val paged = client.listMedia(account, folders, "image/", pageSize = 2).files
        assertTrue("expected several images, got ${whole.size}", whole.size >= 3)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
        assertTrue(whole.all { it.mimeType.startsWith("image/") && it.href.contains(folder.replace(" ", "%20")) })
    }

    @Test
    fun `paged listing matches one unpaged request`() {
        // Read-only, so it is also safe against a real server (Phase 2.2 check on Keith's library).
        val paged = client.listMedia(account, folders, "image/").files
        val whole = client.search(account, folders, "image/", null, 1_000_000)
        assertEquals(whole.size, paged.size)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
    }

    @Test
    fun `downloads a preview by file ID and the original by href`() {
        val photo = client.listMedia(account, folders, "image/").files.first { it.mimeType == "image/jpeg" }
        val preview = File.createTempFile("preview", ".jpg").apply { deleteOnExit() }
        client.downloadPreview(account, photo.fileId, 256, preview, null)
        assertTrue(preview.length() > 0)
        assertEquals(0xFF.toByte(), preview.readBytes()[0]) // JPEG

        val original = File.createTempFile("original", ".jpg").apply { deleteOnExit() }
        client.downloadFile(account, photo.href, original, null)
        assertEquals(photo.sizeBytes, original.length())
    }

    @Test
    fun `files sharing one modification second are all listed`() {
        // Uploads five photos with one modification time, lists them two per page, removes them.
        val path = "$folder/burst-test-${System.nanoTime()}"
        val dir = davUrl(path)
        call(Request.Builder().url(dir).method("MKCOL", null))
        try {
            (1..5).forEach { i ->
                call(Request.Builder().url("$dir/burst-$i.jpg").header("X-OC-Mtime", "1700000000").put(jpeg.toRequestBody()))
            }
            val listed = client.listMedia(account, listOf(path), "image/", pageSize = 2).files
            assertEquals(5, listed.size)
            assertTrue(listed.all { it.lastModifiedMillis == 1_700_000_000_000L })
        } finally {
            call(Request.Builder().url(dir).delete())
        }
    }

    @Test
    fun `etags, one-level listings and favourites reveal a change`() {
        // Uploads one photo into a temporary folder, favourites it, then removes the folder.
        val name = "etag-test-${System.nanoTime()}"
        val dir = davUrl("$folder/$name")
        val rootBefore = client.folderEntry(account, folder)
        call(Request.Builder().url(dir).method("MKCOL", null))
        try {
            call(Request.Builder().url("$dir/photo.jpg").put(jpeg.toRequestBody()))
            assertTrue(rootBefore.etag != client.folderEntry(account, folder).etag)

            val added = client.listSubfolders(account, folders).single { it.href.trimEnd('/').endsWith("/$name") }
            val files = client.listDirectFiles(account, added.href, "image/")
            assertEquals(listOf("photo.jpg"), files.map(RemoteFile::fileName))

            call(
                Request.Builder().url("$dir/photo.jpg").method(
                    "PROPPATCH",
                    """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                        <d:set><d:prop><oc:favorite>1</oc:favorite></d:prop></d:set></d:propertyupdate>""".toRequestBody(),
                ),
            )
            assertTrue(files.single().fileId in client.favoriteIds(account, folders, "image/"))
        } finally {
            call(Request.Builder().url(dir).delete())
        }
    }

    @Test
    fun `one listing covers several folders, and marker files are found`() {
        // a/one.jpg, b/two.jpg, b/inner/three.jpg and b/.nomedia in a temporary folder.
        val base = "$folder/multi-test-${System.nanoTime()}"
        val library = listOf("$base/a", "$base/b")
        listOf(base, "$base/a", "$base/b", "$base/b/inner").forEach { call(Request.Builder().url(davUrl(it)).method("MKCOL", null)) }
        try {
            for (file in listOf("a/one.jpg", "b/two.jpg", "b/inner/three.jpg")) {
                call(Request.Builder().url(davUrl("$base/$file")).put(jpeg.toRequestBody()))
            }
            call(Request.Builder().url(davUrl("$base/b/.nomedia")).put(ByteArray(0).toRequestBody()))

            val listing = client.listMedia(account, library, "image/", pageSize = 2)
            assertEquals(setOf("one.jpg", "two.jpg", "three.jpg"), listing.files.map(RemoteFile::fileName).toSet())
            assertEquals(0, listing.duplicates)

            val home = "/remote.php/dav/files/${account.userId}"
            val markers = client.hidingMarkers(account, library).map { parentFolderKey(it.href) }
            assertEquals(listOf("$home$base/b/"), markers)
            val subfolders = client.listSubfolders(account, library).map { folderKey(it.href) }
            assertEquals(listOf("$home$base/b/inner/"), subfolders)
        } finally {
            call(Request.Builder().url(davUrl(base)).delete())
        }
    }

    @Test(expected = NextcloudHttpException::class)
    fun `a wrong password is an HTTP error, not an empty library`() {
        client.listMedia(account.copy(appPassword = "wrong"), folders, "image/")
    }

    private fun davUrl(path: String) = "${account.baseUrl.trimEnd('/')}/remote.php/dav/files/${account.userId}$path"

    private fun call(request: Request.Builder) =
        http.newCall(request.header("Authorization", Credentials.basic(account.loginName, account.appPassword)).build())
            .execute()
            .use { assertTrue("${it.code} for ${it.request.method}", it.isSuccessful) }
}
