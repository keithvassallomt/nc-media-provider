package com.keithvassallo.ncmediaprovider.data

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

    @Test(expected = NextcloudHttpException::class)
    fun `a wrong password is an HTTP error, not an empty library`() {
        client.listFolder(account.copy(appPassword = "wrong"), folder, "image/")
    }
}
