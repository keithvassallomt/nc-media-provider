package com.keithvassallo.ncmediaprovider.data

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads a remote file of [size] bytes in chunks that [fetch] gets with HTTP Range requests (PLAN
 * 5.2). While one chunk is read, the next is fetched on [prefetcher], so a reader moving through the
 * file rarely waits; a fork of the base project made one request per read, which was slow. Chunks
 * are aligned, so reads that move back and forth a little stay in the same chunk. One reader at a
 * time: calls to [read] must not overlap.
 */
internal class RangeReader(
    val size: Long,
    private val chunkSize: Int = DEFAULT_CHUNK_BYTES,
    private val prefetcher: ExecutorService? = null,
    private val fetch: (offset: Long, length: Int) -> ByteArray,
) {
    private var chunkStart = -1L
    private var chunk = ByteArray(0)
    private var ahead: Pair<Long, Future<ByteArray>>? = null
    private val requestCount = AtomicInteger()
    private val fetchedBytes = AtomicLong()

    val requests: Int get() = requestCount.get()
    val bytesFetched: Long get() = fetchedBytes.get()

    /** Copies up to [length] bytes at [offset] into [into]; fewer only at the end of the file. */
    @Throws(IOException::class)
    fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        if (offset < 0L || offset >= size || length <= 0) return 0
        val wanted = minOf(length.toLong(), size - offset).toInt()
        var done = 0
        while (done < wanted) {
            val position = offset + done
            if (position < chunkStart || position >= chunkStart + chunk.size) load(position)
            val from = (position - chunkStart).toInt()
            val count = minOf(wanted - done, chunk.size - from)
            System.arraycopy(chunk, from, into, intoOffset + done, count)
            done += count
        }
        return done
    }

    fun close() {
        ahead?.second?.cancel(true)
        ahead = null
    }

    private fun load(position: Long) {
        val start = position - position % chunkSize
        val pending = ahead
        ahead = null
        chunk = if (pending != null && pending.first == start) {
            awaitAhead(pending.second)
        } else {
            pending?.second?.cancel(true)
            fetchChunk(start)
        }
        chunkStart = start
        val next = start + chunk.size
        if (prefetcher != null && next < size) ahead = next to prefetcher.submit<ByteArray> { fetchChunk(next) }
    }

    private fun awaitAhead(future: Future<ByteArray>): ByteArray = try {
        future.get()
    } catch (error: ExecutionException) {
        throw (error.cause as? IOException) ?: IOException(error.cause)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while reading ahead", error)
    }

    private fun fetchChunk(start: Long): ByteArray {
        val length = minOf(chunkSize.toLong(), size - start).toInt()
        val bytes = fetch(start, length)
        if (bytes.size != length) throw IOException("Expected $length bytes at $start, got ${bytes.size}")
        requestCount.incrementAndGet()
        fetchedBytes.addAndGet(length.toLong())
        return bytes
    }

    companion object {
        /** With one chunk read ahead, up to 4 MiB is in memory or in flight per stream. */
        const val DEFAULT_CHUNK_BYTES = 2 * 1024 * 1024
    }
}
