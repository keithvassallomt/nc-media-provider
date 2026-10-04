package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Live photos paired by name (#37). */
class LivePhotosTest {
    private fun media(id: String, name: String, durationMillis: Long = 0L, folder: String = "/Photos/") = MediaItem(
        id, "/dav$folder$name", "e$id", name,
        when {
            name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            name.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
            else -> "image/heic"
        },
        10, 1_000, 1_000, durationMillis = durationMillis, folder = folder,
    )

    @Test
    fun `a short MOV with a photo's name is its moving half, whatever Memories says`() {
        val items = listOf(
            // Named as Keith's server names iPhone uploads.
            media("1", "25-04-22 13-42-20 3174.heic"), media("2", "25-04-22 13-42-20 3174.mov", durationMillis = 2_985),
            // "Most compatible" iPhones save a JPEG.
            media("3", "IMG_0001.JPG"), media("4", "IMG_0001.MOV", durationMillis = 1_233),
            // A long video with a photo's name is a video of its own.
            media("5", "IMG_0002.HEIC"), media("6", "IMG_0002.MOV", durationMillis = 45_000),
            // Its length isn't read yet.
            media("7", "IMG_0003.HEIC"), media("8", "IMG_0003.MOV"),
            // Other phones' clips aren't iPhone live photos.
            media("9", "PXL_0004.jpg"), media("10", "PXL_0004.mp4", durationMillis = 2_000),
            // Same name, other folder.
            media("11", "IMG_0005.HEIC"), media("12", "IMG_0005.MOV", durationMillis = 2_000, folder = "/Other/"),
            // No photo at all.
            media("13", "IMG_0006.MOV", durationMillis = 2_000),
        )
        assertEquals(setOf("2", "4"), LivePhotos.byName(items))
        // Memories' pairing still counts, alongside the names.
        val memories = mapOf("7" to MemoriesFile("7", "e7", 1, 1_000, 0, 0, "live-3", isVideo = false))
        assertEquals(setOf("2", "4", "8"), LivePhotos.halves(items, memories::get))
    }
}
