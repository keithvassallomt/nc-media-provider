package com.keithvassallo.ncmediaprovider.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import java.io.File
import java.io.IOException

/**
 * Thumbnails made on the phone when the server can't make them (#33 and #35): Nextcloud makes
 * no HEIC or video previews unless an admin turns them on. All of them are cropped square to fill
 * the tile, as the server's `mode=cover` previews are, and saved as JPEG.
 */
internal object PhoneThumbnails {
    /** From the phone's own copy, through MediaStore: no network at all. */
    fun fromPhoneCopy(resolver: ContentResolver, uri: Uri, sizePx: Int, target: File, cancellationSignal: CancellationSignal?) {
        val bitmap = resolver.loadThumbnail(uri, Size(sizePx, sizePx), cancellationSignal)
        save(cropSquare(bitmap, sizePx), target)
    }

    /**
     * A frame of a server video, read through Range requests: the retriever reads the header and
     * one keyframe, not the file. A second in, or the middle of a shorter clip, avoids a black
     * first frame.
     */
    fun fromVideo(reader: RangeReader, durationMillis: Long, sizePx: Int, target: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(RangeDataSource(reader))
            val atMicros = if (durationMillis in 1..1_999) durationMillis * 500 else 1_000_000L
            val frame = retriever.getScaledFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, sizePx * 2, sizePx * 2)
                ?: throw IOException("No frame in the video")
            save(cropSquare(frame, sizePx), target)
        } finally {
            retriever.release()
        }
    }

    /** From a downloaded image the server can't preview, HEIC mostly; decoded at a reduced size. */
    fun fromImageFile(source: File, sizePx: Int, target: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            val shorter = minOf(info.size.width, info.size.height)
            if (shorter > sizePx * 2) {
                val scale = sizePx * 2f / shorter
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        save(cropSquare(bitmap, sizePx), target)
    }

    /** The centre square, scaled to [sizePx], as the server's `mode=cover` previews are. */
    private fun cropSquare(source: Bitmap, sizePx: Int): Bitmap {
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        val scaled = if (side > sizePx) Bitmap.createScaledBitmap(square, sizePx, sizePx, true) else square
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun save(bitmap: Bitmap, target: File) {
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
    }

    private const val JPEG_QUALITY = 85
}

/** Lets Android's media classes read a server file through Range requests. */
internal class RangeDataSource(private val reader: RangeReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.size) return -1
        return synchronized(reader) { reader.read(position, buffer, offset, size) }
    }

    override fun getSize(): Long = reader.size

    override fun close() = reader.close()
}
