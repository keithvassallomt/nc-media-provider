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

    @Before
    fun requireServer() {
        val url = System.getenv("NC_TEST_URL")
        assumeTrue("NC_TEST_URL not set", url != null)
        val login = System.getenv("NC_TEST_LOGIN") ?: "alice"
        account = NextcloudAccount(url!!, login, login, System.getenv("NC_TEST_PASSWORD").orEmpty())
    }

    @Test
    fun `date-window pages add up to the same listing as one big page`() {
        val whole = client.listFolder(account, folder, "image/", pageSize = 1000)
        val paged = client.listFolder(account, folder, "image/", pageSize = 2)
        assertTrue("expected several images, got ${whole.size}", whole.size >= 3)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
        assertTrue(whole.all { it.mimeType.startsWith("image/") && it.href.contains(folder.replace(" ", "%20")) })
    }

    @Test
    fun `paged listing matches one unpaged request`() {
        // Read-only, so it is also safe against a real server (Phase 2.2 check on Keith's library).
        val paged = client.listFolder(account, folder, "image/")
        val whole = client.search(account, folder, "image/", null, 1_000_000)
        assertEquals(whole.size, paged.size)
        assertEquals(whole.map(RemoteFile::fileId).toSet(), paged.map(RemoteFile::fileId).toSet())
    }

    @Test
    fun `downloads a preview by file ID and the original by href`() {
        val jpeg = client.listFolder(account, folder, "image/").first { it.mimeType == "image/jpeg" }
        val preview = File.createTempFile("preview", ".jpg").apply { deleteOnExit() }
        client.downloadPreview(account, jpeg.fileId, 256, preview, null)
        assertTrue(preview.length() > 0)
        assertEquals(0xFF.toByte(), preview.readBytes()[0]) // JPEG

        val original = File.createTempFile("original", ".jpg").apply { deleteOnExit() }
        client.downloadFile(account, jpeg.href, original, null)
        assertEquals(jpeg.sizeBytes, original.length())
    }

    @Test
    fun `files sharing one modification second are all listed`() {
        // Uploads five photos with one modification time, lists them two per page, removes them.
        val http = OkHttpClient()
        val auth = Credentials.basic(account.loginName, account.appPassword)
        val dir = "${account.baseUrl.trimEnd('/')}/remote.php/dav/files/${account.userId}$folder/burst-test-${System.nanoTime()}"
        fun call(request: Request.Builder) = http.newCall(request.header("Authorization", auth).build()).execute().use {
            assertTrue("${it.code} for ${it.request.method}", it.isSuccessful)
        }
        val jpeg = File("../tools/testserver/library/alice/Photos/2021/Summer/no-exif.jpg").readBytes()
        call(Request.Builder().url(dir).method("MKCOL", null))
        try {
            (1..5).forEach { i ->
                call(Request.Builder().url("$dir/burst-$i.jpg").header("X-OC-Mtime", "1700000000").put(jpeg.toRequestBody()))
            }
            val listed = client.listFolder(account, dir.substringAfter("/files/${account.userId}"), "image/", pageSize = 2)
            assertEquals(5, listed.size)
            assertTrue(listed.all { it.lastModifiedMillis == 1_700_000_000_000L })
        } finally {
            call(Request.Builder().url(dir).delete())
        }
    }

    @Test(expected = NextcloudHttpException::class)
    fun `a wrong password is an HTTP error, not an empty library`() {
        client.listFolder(account.copy(appPassword = "wrong"), folder, "image/")
    }
}
