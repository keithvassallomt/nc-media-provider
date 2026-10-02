package com.keithvassallo.ncmediaprovider.data

import java.io.IOException

/**
 * Reads a video's duration from its `moov` box (PLAN 5.3). Core Nextcloud keeps no video duration
 * and the picker showed 00:00 for every video, so the app reads it from the file itself: MP4 and
 * QuickTime files are a list of boxes, each starting with its size, so the `moov` box is found by
 * reading box headers alone, a few bytes each, wherever the large `mdat` box puts it.
 */
internal object VideoHeader {
    /**
     * The duration in milliseconds, or null if the file has no readable `moov`/`mvhd`. [read]
     * returns [length] bytes at an offset (fewer only at the end of the file).
     */
    @Throws(IOException::class)
    fun durationMillis(size: Long, read: (offset: Long, length: Int) -> ByteArray): Long? {
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
                return mvhdDuration(body)
            }
            offset += boxSize
        }
        return null
    }

    /** `mvhd` is the first child of `moov` in practice; it is looked for among the first few. */
    private fun mvhdDuration(moov: ByteArray): Long? {
        var position = 0
        while (position + 8 <= moov.size) {
            val childSize = u32(moov, position)
            if (type(moov, position + 4) == "mvhd") {
                // After size, type, version and flags: two times, then timescale and duration,
                // each 4 bytes in version 0 and 8 bytes (except the timescale) in version 1.
                val version = moov.getOrNull(position + 8)?.toInt() ?: return null
                val timescaleAt = position + if (version == 1) 28 else 20
                val durationAt = timescaleAt + 4
                val durationBytes = if (version == 1) 8 else 4
                if (durationAt + durationBytes > moov.size) return null
                val timescale = u32(moov, timescaleAt)
                val duration = if (version == 1) u64(moov, durationAt) else u32(moov, durationAt)
                if (timescale <= 0L || duration <= 0L || duration == 0xFFFFFFFFL) return null
                return duration * 1000L / timescale
            }
            if (childSize < 8) return null
            position += childSize.toInt()
        }
        return null
    }

    private fun u32(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private fun u64(bytes: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    private fun type(bytes: ByteArray, at: Int): String =
        if (at + 4 > bytes.size) "" else String(bytes, at, 4, Charsets.ISO_8859_1)

    private const val MAX_TOP_LEVEL_BOXES = 32
    private const val MOOV_PREFIX_BYTES = 4096L
}
