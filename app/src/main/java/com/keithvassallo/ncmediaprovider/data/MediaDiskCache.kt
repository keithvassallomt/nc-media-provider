package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.os.CancellationSignal
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaDiskCache(context: Context) {
    private val root = File(context.applicationContext.cacheDir, "media_cache")
    private val locks = ConcurrentHashMap<String, Any>()
    private val bytesSincePrune = ConcurrentHashMap<Area, AtomicLong>()
    private val pruning = ConcurrentHashMap<Area, AtomicBoolean>()

    /**
     * Returns an already cached file without touching the network. Callers use this before
     * acquiring a download slot so that cache hits never queue behind a slow server.
     */
    fun peek(area: Area, key: String): File? =
        File(File(root, area.directory), key.sha256()).takeIf { it.isUsable() }?.touchForLru()

    fun getOrDownload(
        area: Area,
        key: String,
        cancellationSignal: CancellationSignal?,
        download: (File) -> Unit,
    ): File {
        val directory = File(root, area.directory).apply { mkdirs() }
        val target = File(directory, key.sha256())
        if (target.isUsable()) return target.touchForLru()
        val lock = locks.getOrPut("${area.directory}:$key") { Any() }
        try {
            synchronized(lock) {
                if (target.isUsable()) return target.touchForLru()
                cancellationSignal?.throwIfCanceled()
                val temporary = File(directory, "${target.name}.${System.nanoTime()}.part")
                if (temporary.exists()) temporary.delete()
                try {
                    download(temporary)
                    check(temporary.isUsable()) { "The server returned an empty media file" }
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                    target.touchForLru()
                    pruneIfDue(area, directory, target)
                    return target
                } catch (error: Throwable) {
                    temporary.delete()
                    throw error
                }
            }
        } finally {
            locks.remove("${area.directory}:$key", lock)
        }
    }

    fun clear() {
        root.deleteRecursively()
        bytesSincePrune.values.forEach { it.set(0L) }
    }

    fun stats(): CacheStats = CacheStats(
        previews = areaStats(Area.PREVIEW),
        originals = areaStats(Area.ORIGINAL),
    )

    private fun areaStats(area: Area): CacheAreaStats {
        val usedBytes = File(root, area.directory)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sumOf { file -> runCatching { file.length() }.getOrDefault(0L) }
            ?: 0L
        return CacheAreaStats(usedBytes = usedBytes, maximumBytes = area.maximumBytes)
    }

    /**
     * Scanning the whole cache directory costs one stat per file, which is far too expensive to
     * repeat for every thumbnail the picker grid requests. Amortise it over many downloads instead.
     */
    private fun pruneIfDue(area: Area, directory: File, keep: File) {
        val written = bytesSincePrune.getOrPut(area) { AtomicLong() }.addAndGet(keep.length())
        if (written < area.maximumBytes / PRUNE_INTERVAL_DIVISOR) return
        val guard = pruning.getOrPut(area) { AtomicBoolean() }
        if (!guard.compareAndSet(false, true)) return
        try {
            bytesSincePrune.getValue(area).set(0L)
            prune(directory, area.maximumBytes, keep)
        } finally {
            guard.set(false)
        }
    }

    private fun prune(directory: File, maximumBytes: Long, keep: File) {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }.orEmpty()
        var total = files.sumOf(File::length)
        if (total <= maximumBytes) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (total <= maximumBytes) return
            if (file != keep) {
                val length = file.length()
                if (file.delete()) total -= length
            }
        }
    }

    private fun File.isUsable() = isFile && length() > 0L

    private fun File.touchForLru(): File = apply { setLastModified(System.currentTimeMillis()) }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    enum class Area(val directory: String, val maximumBytes: Long) {
        PREVIEW("previews", 256L * 1024L * 1024L),
        ORIGINAL("originals", 2L * 1024L * 1024L * 1024L),
    }

    private companion object {
        const val PRUNE_INTERVAL_DIVISOR = 16L
    }
}

data class CacheStats(
    val previews: CacheAreaStats,
    val originals: CacheAreaStats,
)

data class CacheAreaStats(
    val usedBytes: Long,
    val maximumBytes: Long,
)
