package com.keithvassallo.ncmediaprovider.data

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Memories' timeline API is internal and undocumented (#41), so its parsing runs against real
 * responses saved from the test servers: Memories 8.1.0 on Nextcloud 33, 9.0.1 on Nextcloud 35.
 */
class MemoriesApiTest {
    private fun fixture(server: String, name: String) = File("../testdata/nextcloud/$server/$name").readText()

    @Test
    fun `reads the version, and only tested versions are used`() {
        assertEquals("8.1.0", MemoriesApi.parseVersion(fixture("33-full", "memories-describe.json")))
        assertEquals("9.0.1", MemoriesApi.parseVersion(fixture("35-full", "memories-describe.json")))
        assertNull(MemoriesApi.parseVersion("<html>Not found</html>"))
        assertTrue(MemoriesApi.isSupported("8.1.0"))
        assertTrue(MemoriesApi.isSupported("9.0.1"))
        assertFalse(MemoriesApi.isSupported("7.3.1"))
        assertFalse(MemoriesApi.isSupported("10.0.0"))
        assertFalse(MemoriesApi.isSupported("dev"))
    }

    @Test
    fun `reads the day list and the files of both tested versions`() {
        for (server in listOf("33-full", "35-full")) {
            val days = MemoriesApi.parseDays(fixture(server, "memories-days.json"))
            assertEquals(server, 8, days.size)
            assertTrue(server, days.values.all { it == 1 })

            val files = MemoriesApi.parseFiles(fixture(server, "memories-day.json")).associateBy { it.id }
            assertEquals(server, 8, files.size)
            assertEquals(server, days.keys, files.values.map(MemoriesFile::dayId).toSet())
            // The video's own recording time; core dates it by its upload time.
            val clip = files.getValue("108")
            assertTrue(clip.isVideo)
            assertEquals(1_683_374_400_000L, clip.dateTakenMillis)
            assertEquals(1280 to 720, clip.width to clip.height)
            // A HEIC's EXIF date, which core never reads.
            assertEquals(1_646_470_800_000L, files.getValue("105").dateTakenMillis)
            // Dimensions in display orientation, as core's are: stored 1200x800 with EXIF orientation 6.
            assertEquals(800 to 1200, files.getValue("104").let { it.width to it.height })
            assertTrue(files.values.none { it.liveId != null })
            assertTrue(files.values.all { it.etag.length == 32 })
        }
    }

    @Test
    fun `files missing what the layer needs are skipped, and photos with the motion inside aren't live pairs`() {
        val files = MemoriesApi.parseFiles(
            """
            [
              {"fileid": 1, "dayid": 20722, "etag": "a", "epoch": 1790451440, "w": 3064, "h": 4080, "liveid": "self__exifbin=MotionPhotoVideo"},
              {"fileid": 2, "dayid": 20722, "etag": "b", "epoch": 1790423241, "liveid": "6A0E2B1C-ABCD"},
              {"fileid": 3, "dayid": 20722, "etag": "c"},
              {"fileid": 4, "dayid": 20722, "epoch": 1790423241},
              {"dayid": 20722, "etag": "e", "epoch": 1790423241},
              "not a file"
            ]
            """.trimIndent(),
        )
        assertEquals(listOf("1", "2"), files.map(MemoriesFile::id))
        assertNull(files[0].liveId)
        assertEquals("6A0E2B1C-ABCD", files[1].liveId)
        assertEquals(0 to 0, files[1].width to files[1].height)
        assertFalse(files[1].isVideo)
    }

    @Test(expected = JSONException::class)
    fun `an answer that isn't a day list fails, so the layer can fall back`() {
        MemoriesApi.parseDays("""{"message": "Unauthorized"}""")
    }

    @Test
    fun `only new days, changed counts and days with stale files are read again`() {
        val stored = mapOf(1 to 3, 2 to 5, 3 to 1, 4 to 2)
        val current = mapOf(1 to 3, 2 to 6, 4 to 2, 5 to 1)
        // Day 3 is gone: its files are dropped, not read. Day 9 holds a stale file but no longer exists.
        assertEquals(setOf(2, 4, 5), MemoriesApi.changedDays(current, stored, staleDays = listOf(4, 9)))
        assertEquals(current.keys, MemoriesApi.changedDays(current, emptyMap(), emptyList()))
        assertTrue(MemoriesApi.changedDays(current, current, emptyList()).isEmpty())
    }

    @Test
    fun `requests hold up to a thousand files and a hundred days, newest first`() {
        val counts = mapOf(10 to 600, 11 to 300, 12 to 200, 13 to 1_500)
        assertEquals(listOf(listOf(13), listOf(12, 11), listOf(10)), MemoriesApi.requestBatches(counts.keys, counts))
        val many = (1..250).associateWith { 1 }
        assertEquals(listOf(100, 100, 50), MemoriesApi.requestBatches(many.keys, many).map(List<Int>::size))
        assertTrue(MemoriesApi.requestBatches(emptyList(), counts).isEmpty())
    }

    private fun media(id: String, name: String, folder: String = "/Photos/") = MediaItem(
        id, "/dav$folder$name", "e$id", name, if (name.endsWith(".MOV")) "video/quicktime" else "image/heic", 10, 1_000, 1_000,
        folder = folder,
    )

    private fun indexed(id: String, liveId: String? = null) = MemoriesFile(id, "e$id", 1, 1_000, 0, 0, liveId, isVideo = false)

    @Test
    fun `a video is a live photo's half when Memories pairs the photo and leaves the video out`() {
        val items = listOf(
            media("1", "IMG_0001.HEIC"), media("2", "IMG_0001.MOV"),
            // Memories shows this video, so it's a video of its own.
            media("3", "IMG_0002.HEIC"), media("4", "IMG_0002.MOV"),
            // A photo without a live-photo ID.
            media("5", "IMG_0003.HEIC"), media("6", "IMG_0003.MOV"),
            // Same name, other folder.
            media("7", "img_0004.heic"), media("8", "IMG_0004.MOV", folder = "/Other/"),
            // Names compared without case.
            media("9", "img_0005.heic"), media("10", "IMG_0005.MOV"),
        )
        val memories = listOf(
            indexed("1", "live-1"), indexed("3", "live-2"), indexed("4"), indexed("5"), indexed("7", "live-4"), indexed("9", "live-5"),
        ).associateBy(MemoriesFile::id)
        assertEquals(setOf("2", "10"), MemoriesApi.liveHalves(items, memories::get))
        assertTrue(MemoriesApi.liveHalves(items) { null }.isEmpty())
    }
}
