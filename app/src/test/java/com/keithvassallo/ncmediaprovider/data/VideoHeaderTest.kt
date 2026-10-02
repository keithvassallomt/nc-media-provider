package com.keithvassallo.ncmediaprovider.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class VideoHeaderTest {
    private var bytesRead = 0

    private fun durationOf(file: ByteArray): Long? = VideoHeader.durationMillis(file.size.toLong()) { offset, length ->
        bytesRead += length
        file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
    }

    @Test
    fun `reads the test library's videos, reading only box headers and the start of moov`() {
        val library = File("../tools/testserver/library/alice/Photos")
        // ffprobe: 3.0 s and 2.0 s.
        assertEquals(3_000L, durationOf(File(library, "2023/clip.mp4").readBytes()))
        bytesRead = 0
        val mov = File(library, "2022/IMG_1001.MOV").readBytes()
        assertEquals(2_000L, durationOf(mov))
        assertTrue("read $bytesRead of ${mov.size} bytes", bytesRead <= 4096 + 16 * 8)
    }

    private fun box(type: String, body: ByteArray, largeSize: Boolean = false): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).run {
            if (largeSize) {
                writeInt(1)
                writeBytes(type)
                writeLong(16L + body.size)
            } else {
                writeInt(8 + body.size)
                writeBytes(type)
            }
            write(body)
        }
    }.toByteArray()

    private fun mvhd(version: Int, timescale: Int, duration: Long): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).run {
            writeInt(version shl 24)
            if (version == 1) {
                writeLong(0); writeLong(0); writeInt(timescale); writeLong(duration)
            } else {
                writeInt(0); writeInt(0); writeInt(timescale); writeInt(duration.toInt())
            }
            write(ByteArray(80))
        }
    }.toByteArray()

    @Test
    fun `moov after a large mdat with a 64-bit size, and a version 1 mvhd`() {
        val file = box("ftyp", "isom0000".toByteArray()) +
            box("mdat", ByteArray(100_000), largeSize = true) +
            box("moov", box("mvhd", mvhd(1, 90_000, 90_000L * 185)) + box("trak", ByteArray(64)))
        bytesRead = 0
        assertEquals(185_000L, durationOf(file))
        assertTrue("read $bytesRead bytes", bytesRead < 5_000)
    }

    @Test
    fun `no moov, or a broken box, gives no duration`() {
        assertNull(durationOf(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(1_000))))
        assertNull(durationOf(byteArrayOf(0, 0, 0, 3, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertNull(durationOf(box("moov", box("mvhd", mvhd(0, 0, 100)))))
    }
}
