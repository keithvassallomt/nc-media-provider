package com.keithvassallo.ncmediaprovider.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The originals' limit as a setting, "Clear downloads" and the pre-cache switch (#48). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaDiskCacheTest {
    private var originalsLimit = 100_000L
    private val cache = MediaDiskCache(ApplicationProvider.getApplicationContext()) { area ->
        if (area == MediaDiskCache.Area.ORIGINAL) originalsLimit else area.maximumBytes
    }

    private fun put(area: MediaDiskCache.Area, key: String, bytes: Int, ageMillis: Long) =
        cache.getOrDownload(area, key, null) { it.writeBytes(ByteArray(bytes)) }
            .apply { setLastModified(System.currentTimeMillis() - ageMillis) }

    @Test
    fun `lowering the originals' limit trims the oldest-used at once`() {
        put(MediaDiskCache.Area.ORIGINAL, "old", 4_000, ageMillis = 30_000)
        put(MediaDiskCache.Area.ORIGINAL, "middle", 4_000, ageMillis = 20_000)
        put(MediaDiskCache.Area.ORIGINAL, "new", 4_000, ageMillis = 10_000)
        assertEquals(12_000L, cache.stats().originals.usedBytes)

        originalsLimit = 5_000L
        cache.prune(MediaDiskCache.Area.ORIGINAL)
        assertEquals(5_000L, cache.stats().originals.maximumBytes)
        assertNull(cache.peek(MediaDiskCache.Area.ORIGINAL, "old"))
        assertNull(cache.peek(MediaDiskCache.Area.ORIGINAL, "middle"))
        assertNotNull(cache.peek(MediaDiskCache.Area.ORIGINAL, "new"))
    }

    @Test
    fun `clearing one area leaves the others`() {
        put(MediaDiskCache.Area.PREVIEW, "p", 100, ageMillis = 0)
        put(MediaDiskCache.Area.PRECACHE, "t", 100, ageMillis = 0)
        cache.clear(MediaDiskCache.Area.PREVIEW)
        assertNull(cache.peek(MediaDiskCache.Area.PREVIEW, "p"))
        assertNotNull(cache.peek(MediaDiskCache.Area.PRECACHE, "t"))
        // An area that was never used can be cleared and pruned too.
        cache.clear(MediaDiskCache.Area.ORIGINAL)
        cache.prune(MediaDiskCache.Area.ORIGINAL)
    }
}
