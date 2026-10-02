package com.keithvassallo.ncmediaprovider.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors

class RangeReaderTest {
    private val file = ByteArray(10_000) { (it * 31 % 251).toByte() }
    private val fetched = mutableListOf<Pair<Long, Int>>()
    private val pool = Executors.newSingleThreadExecutor()

    @After
    fun stop() {
        pool.shutdownNow()
    }

    private fun reader(chunk: Int = 1_000, ahead: Boolean = false) = RangeReader(file.size.toLong(), chunk, if (ahead) pool else null) { offset, length ->
        synchronized(fetched) { fetched += offset to length }
        file.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun readAll(reader: RangeReader, step: Int): ByteArray {
        val out = ByteArray(file.size)
        var offset = 0
        while (offset < file.size) offset += reader.read(offset.toLong(), out, offset, step)
        return out
    }

    @Test
    fun `sequential reads of any size give the file, one request per chunk`() {
        for (step in listOf(1, 333, 1_000, 4_096, 20_000)) {
            fetched.clear()
            val reader = reader()
            assertArrayEquals("step $step", file, readAll(reader, step))
            assertEquals("step $step", 10, reader.requests)
        }
    }

    @Test
    fun `chunks are aligned and the last one is short`() {
        val reader = RangeReader(2_500, 1_000) { offset, length ->
            fetched += offset to length
            ByteArray(length)
        }
        reader.read(1_500, ByteArray(1_000), 0, 1_000)
        reader.read(2_400, ByteArray(500), 0, 500)
        assertEquals(listOf(1_000L to 1_000, 2_000L to 500), fetched)
    }

    @Test
    fun `reads past the end return what there is`() {
        val reader = reader()
        val out = ByteArray(100)
        assertEquals(50, reader.read(9_950, out, 0, 100))
        assertEquals(0, reader.read(10_000, out, 0, 100))
    }

    @Test
    fun `reading ahead fetches the next chunk before it is asked for`() {
        val reader = reader(ahead = true)
        assertArrayEquals(file, readAll(reader, 500))
        assertEquals(10, reader.requests)
        assertEquals((0 until 10).map { it * 1_000L to 1_000 }, synchronized(fetched) { fetched.sortedBy { it.first } })
    }

    @Test
    fun `a jump backwards fetches again, and a failed fetch surfaces as IOException`() {
        val reader = reader()
        reader.read(5_000, ByteArray(10), 0, 10)
        reader.read(0, ByteArray(10), 0, 10)
        assertEquals(listOf(5_000L to 1_000, 0L to 1_000), fetched)
        val failing = RangeReader(100, 10) { _, _ -> throw IOException("offline") }
        val error = runCatching { failing.read(0, ByteArray(10), 0, 10) }.exceptionOrNull()
        assertTrue(error is IOException)
    }
}
