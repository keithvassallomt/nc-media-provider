package com.keithvassallo.ncmediaprovider.data

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
        val whole = client.listMedia(account, folders, listOf("image/"), pageSize = 1000).files
        val paged = client.listMedia(account, folders, listOf("image/"), pageSize = 2).files
        assertTrue("expected several images, got ${whole.size}", whole.size >= 3)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
        assertTrue(whole.all { it.mimeType.startsWith("image/") && it.href.contains(folder.replace(" ", "%20")) })
    }

    @Test
    fun `paged listing matches one unpaged request`() {
        // Read-only, so it is also safe against a real server (Phase 2.2 check on Keith's library).
        val paged = client.listMedia(account, folders, listOf("image/")).files
        val whole = client.search(account, folders, listOf("image/"), null, 1_000_000)
        assertEquals(whole.size, paged.size)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
    }

    @Test
    fun `downloads a preview by file ID and the original by href`() {
        val photo = client.listMedia(account, folders, listOf("image/")).files.first { it.mimeType == "image/jpeg" }
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
            val listed = client.listMedia(account, listOf(path), listOf("image/"), pageSize = 2).files
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
            val files = client.listDirectFiles(account, added.href, listOf("image/"))
            assertEquals(listOf("photo.jpg"), files.map(RemoteFile::fileName))

            call(
                Request.Builder().url("$dir/photo.jpg").method(
                    "PROPPATCH",
                    """<?xml version="1.0"?><d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
                        <d:set><d:prop><oc:favorite>1</oc:favorite></d:prop></d:set></d:propertyupdate>""".toRequestBody(),
                ),
            )
            assertTrue(files.single().fileId in client.favoriteIds(account, folders, listOf("image/")))
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

            val listing = client.listMedia(account, library, listOf("image/"), pageSize = 2)
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

    @Test
    fun `sign-in endpoints answer before approval`() {
        val status = client.serverStatus(account.baseUrl)
        assertTrue(status.installed)
        assertTrue("version ${status.version}", status.majorVersion >= 30)
        val flow = client.startLogin(account.baseUrl)
        assertTrue(flow.loginUrl, flow.loginUrl.contains("/login/v2/flow/"))
        assertNull(client.pollLogin(account.baseUrl, flow))
        assertEquals(account.userId, client.userId(account))
        // Memories may or may not be installed; either answer is fine, an error is not.
        client.memoriesTimelinePaths(account)
        val children = client.listChildFolders(account, folder)
        assertTrue(children.isNotEmpty())
        assertTrue(children.none { folderKey(it.href).endsWith("$folder/") })
    }

    @Test
    fun `Memories, when installed, dates files by their own etag`() {
        // Read-only, so it is also safe against a real server.
        val version = client.memoriesVersion(account)
        assumeTrue("Memories not installed", version != null)
        val days = client.memoriesDays(account)
        val files = MemoriesApi.requestBatches(days.keys, days).flatMap { client.memoriesFiles(account, it) }
        assertEquals(days.values.sum(), files.size)
        // Overrides apply only while the etags agree, so they must be the same strings WebDAV lists.
        val listed = client.listMedia(account, folders, listOf("image/", "video/")).files.associateBy(RemoteFile::fileId)
        val shared = files.filter { it.id in listed }
        assertTrue("no file both listed and in Memories", shared.isNotEmpty())
        assertTrue(shared.all { listed.getValue(it.id).etag == it.etag })
    }

    @Test
    fun `albums list, and their files open through the album, previews through Photos`() {
        // Read-only, so it is also safe against a real server.
        val albums = client.listAlbums(account)
        assumeTrue("Photos app not available", albums != null)
        for (album in albums!!) {
            val files = client.listDirectFiles(account, album.href, listOf("image/", "video/"))
            assertTrue(album.name, files.all { Albums.isAlbumPath(it.href) })
            val photo = files.firstOrNull { it.mimeType == "image/jpeg" } ?: continue
            val preview = File.createTempFile("album-preview", ".jpg").apply { deleteOnExit() }
            client.downloadPreview(account, photo.fileId, 256, preview, null, viaPhotos = true)
            assertEquals(0xFF.toByte(), preview.readBytes()[0])
            val original = File.createTempFile("album-original", ".jpg").apply { deleteOnExit() }
            client.downloadFile(account, photo.href, original, null)
            assertEquals(photo.sizeBytes, original.length())
            assertEquals(16, client.fetchRange(account, photo.href, photo.etag, 0, 16).size)
        }
        // The test servers' "Road trip": bob shares it, and one photo is only in the album, where
        // core's preview endpoint can't see it.
        val shared = albums.firstOrNull { it.isShared && it.name == "Road trip (bob)" } ?: return
        val albumOnly = client.listDirectFiles(account, shared.href, listOf("image/")).single { it.fileName.endsWith("-bob-only.jpg") }
        try {
            client.downloadPreview(account, albumOnly.fileId, 256, File.createTempFile("core", ".jpg").apply { deleteOnExit() }, null)
            fail("core's preview endpoint served a file shared only through an album")
        } catch (error: NextcloudHttpException) {
            assertEquals(404, error.statusCode)
        }
    }

    @Test
    fun `an app password checks for a wipe and can be revoked`() {
        // Nextcloud's own endpoint turns the test password into an app password.
        val url = "${account.baseUrl.trimEnd('/')}/ocs/v2.php/core/getapppassword?format=json"
        val appPassword = http.newCall(
            Request.Builder().url(url)
                .header("Authorization", Credentials.basic(account.loginName, account.appPassword))
                .header("OCS-APIRequest", "true")
                .build(),
        ).execute().use { JSONObject(it.body.string()).getJSONObject("ocs").getJSONObject("data").getString("apppassword") }
        val appAccount = account.copy(appPassword = appPassword)
        assertEquals(account.userId, client.userId(appAccount))
        assertFalse(client.wipeRequested(account.baseUrl, appPassword))
        client.revokeAppPassword(appAccount)
        try {
            client.userId(appAccount)
            fail("a revoked app password still worked")
        } catch (error: NextcloudHttpException) {
            assertEquals(401, error.statusCode)
        }
    }

    @Test
    fun `videos are listed and read by Range, pinned to their etag`() {
        val media = client.listMedia(account, folders, listOf("image/", "video/")).files
        val video = media.filter { it.mimeType.startsWith("video/") && !it.isHidden }.maxByOrNull(RemoteFile::sizeBytes)
        assertTrue("no visible video in $folder", video != null)
        val whole = File.createTempFile("video", ".bin").apply { deleteOnExit() }
        client.downloadFile(account, video!!.href, whole, null)
        val bytes = whole.readBytes()
        val reader = RangeReader(video.sizeBytes, chunkSize = 4096) { offset, length -> client.fetchRange(account, video.href, video.etag, offset, length) }
        val middle = ByteArray(10_000)
        assertEquals(10_000, reader.read(1_000, middle, 0, 10_000))
        assertTrue(middle.contentEquals(bytes.copyOfRange(1_000, 11_000)))
        try {
            client.fetchRange(account, video.href, "not-the-etag", 0, 10)
            fail("a stale etag still read")
        } catch (error: NextcloudHttpException) {
            assertEquals(412, error.statusCode)
        }
    }

    @Test(expected = NextcloudHttpException::class)
    fun `a wrong password is an HTTP error, not an empty library`() {
        client.listMedia(account.copy(appPassword = "wrong"), folders, listOf("image/"))
    }

    private fun davUrl(path: String) = "${account.baseUrl.trimEnd('/')}/remote.php/dav/files/${account.userId}$path"

    private fun call(request: Request.Builder) =
        http.newCall(request.header("Authorization", Credentials.basic(account.loginName, account.appPassword)).build())
            .execute()
            .use { assertTrue("${it.code} for ${it.request.method}", it.isSuccessful) }
}
