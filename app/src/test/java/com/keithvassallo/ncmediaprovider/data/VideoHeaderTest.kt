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

    private fun headerOf(file: ByteArray): VideoHeader.Info? = VideoHeader.read(file.size.toLong(), NOW) { offset, length ->
        bytesRead += length
        file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
    }

    private fun durationOf(file: ByteArray): Long? = headerOf(file)?.durationMillis

    @Test
    fun `reads the test library's videos, reading only box headers and the start of moov`() {
        val library = File("../tools/testserver/library/alice/Photos")
        // ffprobe: 3.0 s recorded 2023-05-06 12:00:00 UTC, and 2.0 s recorded 2022-03-05 09:00:00 UTC.
        assertEquals(VideoHeader.Info(3_000L, 1_683_374_400_000L), headerOf(File(library, "2023/clip.mp4").readBytes()))
        bytesRead = 0
        val mov = File(library, "2022/IMG_1001.MOV").readBytes()
        assertEquals(VideoHeader.Info(2_000L, 1_646_470_800_000L), headerOf(mov))
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

    private fun mvhd(version: Int, timescale: Int, duration: Long, created: Long = 0L): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).run {
            writeInt(version shl 24)
            if (version == 1) {
                writeLong(created); writeLong(0); writeInt(timescale); writeLong(duration)
            } else {
                writeInt(created.toInt()); writeInt(0); writeInt(timescale); writeInt(duration.toInt())
            }
            write(ByteArray(80))
        }
    }.toByteArray()

    @Test
    fun `moov after a large mdat with a 64-bit size, and a version 1 mvhd`() {
        val file = box("ftyp", "isom0000".toByteArray()) +
            box("mdat", ByteArray(100_000), largeSize = true) +
            box("moov", box("mvhd", mvhd(1, 90_000, 90_000L * 185, created = 3_787_009_445L)) + box("trak", ByteArray(64)))
        bytesRead = 0
        // Recorded 2024-01-02 03:04:05 UTC: seconds since 1904.
        assertEquals(VideoHeader.Info(185_000L, 1_704_164_645_000L), headerOf(file))
        assertTrue("read $bytesRead bytes", bytesRead < 5_000)
    }

    @Test
    fun `no moov, or a broken box, gives no duration`() {
        assertNull(durationOf(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(1_000))))
        assertNull(durationOf(byteArrayOf(0, 0, 0, 3, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())))
        assertNull(durationOf(box("moov", box("mvhd", mvhd(0, 0, 100)))))
    }

    @Test
    fun `an unset or impossible recording time is dropped, the duration kept`() {
        fun recorded(created: Long) = headerOf(box("moov", box("mvhd", mvhd(0, 1_000, 5_000, created))))
        assertEquals(VideoHeader.Info(5_000L, null), recorded(0L))
        // 1904 plus a few years: a camera whose clock was never set.
        assertEquals(VideoHeader.Info(5_000L, null), recorded(100_000_000L))
        // Two days after "now".
        assertEquals(VideoHeader.Info(5_000L, null), recorded(NOW / 1000 + 2_082_844_800L + 2 * 86_400L))
        assertEquals(VideoHeader.Info(5_000L, NOW - 1_000L), recorded(NOW / 1000 + 2_082_844_800L - 1L))
    }

    private companion object {
        /** 2026-10-03 00:00:00 UTC. */
        const val NOW = 1_790_985_600_000L
    }
}
