package com.keithvassallo.ncmediaprovider.data

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Thumbnails made on the phone for files a server can't preview (#33, #35), on a real phone's
 * codecs. Needs the default test server (no HEIC or video previews) reachable from the phone:
 *   tools/testserver/up.sh 35 default && adb reverse tcp:8035 tcp:8035
 * then run with `am instrument` (see docs/device-notes.md), which leaves the app installed.
 */
@RunWith(AndroidJUnit4::class)
class PhoneThumbnailsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val client = NextcloudClient()
    private val account = NextcloudAccount("http://127.0.0.1:8035", "alice", "alice", "throwaway-35-default")
    private lateinit var media: List<RemoteFile>

    @Before
    fun requireServer() {
        media = runCatching { client.listMedia(account, listOf("/Photos"), listOf("image/", "video/")).files }.getOrDefault(emptyList())
        assumeTrue("test server not reachable", media.isNotEmpty())
    }

    private fun file(name: String) = media.single { it.fileName == name }

    private fun assertSquareJpeg(target: File) {
        val bitmap = BitmapFactory.decodeFile(target.path)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }

    @Test
    fun aVideoFrameComesFromRangeRequests() {
        val video = file("clip.mp4")
        val noPreview = runCatching { client.downloadPreview(account, video.fileId, 256, File(context.cacheDir, "p"), null) }
        assertEquals(404, (noPreview.exceptionOrNull() as NextcloudHttpException).statusCode)
        val reader = RangeReader(video.sizeBytes, 512 * 1024) { offset, length -> client.fetchRange(account, video.href, video.etag, offset, length) }
        val target = File(context.cacheDir, "frame.jpg")
        PhoneThumbnails.fromVideo(reader, 3_000, 256, target)
        assertSquareJpeg(target)
    }

    @Test
    fun aHeicIsDecodedFromItsDownload() {
        val heic = file("portrait.heic")
        val noPreview = runCatching { client.downloadPreview(account, heic.fileId, 256, File(context.cacheDir, "p"), null) }
        assertEquals(404, (noPreview.exceptionOrNull() as NextcloudHttpException).statusCode)
        val original = File(context.cacheDir, "portrait.heic")
        client.downloadFile(account, heic.href, original, null)
        val target = File(context.cacheDir, "heic.jpg")
        PhoneThumbnails.fromImageFile(original, 256, target)
        assertSquareJpeg(target)
    }
}
