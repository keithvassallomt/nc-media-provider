package com.keithvassallo.ncmediaprovider.data

import java.io.IOException

/**
 * Reads a video's duration and recording time from its `moov` box (PLAN 5.3 and 6.0). Core
 * Nextcloud keeps no video duration, and dates videos by their file name or upload time, so the app
 * reads both from the file itself: MP4 and QuickTime files are a list of boxes, each starting with
 * its size, so the `moov` box is found by reading box headers alone, a few bytes each, wherever the
 * large `mdat` box puts it.
 */
internal object VideoHeader {
    /** What the `mvhd` box says; either value is null when missing or implausible. */
    data class Info(val durationMillis: Long?, val recordedMillis: Long?)

    /**
     * The header, or null if the file has no readable `moov`/`mvhd`. [read] returns [length] bytes
     * at an offset (fewer only at the end of the file). A recording time after [nowMillis] (plus a
     * day for clock drift) or before 1990 is a camera's unset clock, and is dropped.
     */
    @Throws(IOException::class)
    fun read(size: Long, nowMillis: Long = System.currentTimeMillis(), read: (offset: Long, length: Int) -> ByteArray): Info? {
        var offset = 0L
        repeat(MAX_TOP_LEVEL_BOXES) {
            if (offset + 8 > size) return null
            val header = read(offset, minOf(16L, size - offset).toInt())
            if (header.size < 8) return null
            val declared = u32(header, 0)
            val headerLength = if (declared == 1L) 16 else 8
            val boxSize = when (declared) {
                0L -> size - offset
                1L -> if (header.size < 16) return null else u64(header, 8)
                else -> declared
            }
            if (boxSize < headerLength) return null
            if (type(header, 4) == "moov") {
                val body = read(offset + headerLength, minOf(boxSize - headerLength, MOOV_PREFIX_BYTES).toInt())
                return mvhd(body, nowMillis)
            }
            offset += boxSize
        }
        return null
    }

    /** `mvhd` is the first child of `moov` in practice; it is looked for among the first few. */
    private fun mvhd(moov: ByteArray, nowMillis: Long): Info? {
        var position = 0
        while (position + 8 <= moov.size) {
            val childSize = u32(moov, position)
            if (type(moov, position + 4) == "mvhd") {
                // After size, type, version and flags: the creation and modification times, then
                // the timescale and duration, each 4 bytes in version 0 and 8 bytes (except the
                // timescale) in version 1.
                val version = moov.getOrNull(position + 8)?.toInt() ?: return null
                val createdAt = position + 12
                val timescaleAt = position + if (version == 1) 28 else 20
                val durationAt = timescaleAt + 4
                val wide = if (version == 1) 8 else 4
                if (durationAt + wide > moov.size) return null
                val created = if (version == 1) u64(moov, createdAt) else u32(moov, createdAt)
                val timescale = u32(moov, timescaleAt)
                val duration = if (version == 1) u64(moov, durationAt) else u32(moov, durationAt)
                val durationMillis = if (timescale <= 0L || duration <= 0L || duration == 0xFFFFFFFFL) null else duration * 1000L / timescale
                return Info(durationMillis, recordedMillis(created, nowMillis))
            }
            if (childSize < 8) return null
            position += childSize.toInt()
        }
        return null
    }

    /** `creation_time` counts seconds from 1904 in UTC; 0 means unset. */
    private fun recordedMillis(secondsSince1904: Long, nowMillis: Long): Long? {
        if (secondsSince1904 <= 0L) return null
        val millis = (secondsSince1904 - SECONDS_1904_TO_1970) * 1000L
        return millis.takeIf { it >= EARLIEST_PLAUSIBLE_MILLIS && it <= nowMillis + CLOCK_SLACK_MILLIS }
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private fun u64(bytes: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private fun type(bytes: ByteArray, at: Int): String =
        if (at + 4 > bytes.size) "" else String(bytes, at, 4, Charsets.ISO_8859_1)

    private const val MAX_TOP_LEVEL_BOXES = 32
    private const val MOOV_PREFIX_BYTES = 4096L
    private const val SECONDS_1904_TO_1970 = 2_082_844_800L

    /** 1990-01-01 UTC. */
    private const val EARLIEST_PLAUSIBLE_MILLIS = 631_152_000_000L
    private const val CLOCK_SLACK_MILLIS = 24L * 60L * 60L * 1_000L
}
