package com.keithvassallo.ncmediaprovider.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class LocalMediaIndex(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val warmScheduled = AtomicBoolean(false)
    private val warmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nc-local-media-index").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    @Volatile
    private var snapshot: Snapshot? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            invalidate()
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            invalidate()
        }
    }

    init {
        appContext.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
        appContext.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
    }

    fun hasFullAccess(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    fun invalidate() {
        snapshot = null
    }

    fun warm() {
        if (!hasAnyMediaPermission() || !warmScheduled.compareAndSet(false, true)) return
        warmExecutor.execute {
            try {
                currentSnapshot()
            } finally {
                warmScheduled.set(false)
            }
        }
    }

    /**
     * Finds the phone's own copy of a server file. Phone uploads keep their original file name, so
     * name and size settle most cases; a renamed upload still matches on type and a capture time
     * within [DATE_TOLERANCE_MS]. Ambiguous candidates never match.
     */
    fun find(fileName: String, sizeBytes: Long, dateTakenMillis: Long, mimeType: String): Uri? {
        // Checked before the permission lookup: find() runs once per row of every media cursor, and
        // the snapshot check is a plain field read while a permission check is not.
        val index = readySnapshot() ?: run {
            warm()
            return null
        }
        if (!hasPermissionFor(mimeType)) return null
        val name = fileName.trim().takeIf(String::isNotEmpty) ?: return null
        if (sizeBytes > 1L) {
            val exactMatches = index.exact[ExactKey(name.normalized(), sizeBytes)].orEmpty()
            if (exactMatches.size == 1) return exactMatches.single().uri
            selectByDate(exactMatches, dateTakenMillis)?.let { return it.uri }
        }
        val sameName = index.byName[name.normalized()].orEmpty()
            .filter { it.mimeType.substringBefore('/') == mimeType.substringBefore('/') }
        return selectByDate(sameName, dateTakenMillis)?.uri
    }

    private fun hasPermissionFor(mimeType: String): Boolean {
        val permission = if (mimeType.startsWith("video/")) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

    private fun currentSnapshot(): Snapshot {
        val now = System.currentTimeMillis()
        snapshot?.takeIf { now - it.createdAtMillis < CACHE_TTL_MS }?.let { return it }
        synchronized(lock) {
            snapshot?.takeIf { now - it.createdAtMillis < CACHE_TTL_MS }?.let { return it }
            return buildSnapshot(now).also { snapshot = it }
        }
    }

    private fun readySnapshot(): Snapshot? {
        val current = snapshot ?: return null
        if (System.currentTimeMillis() - current.createdAtMillis >= CACHE_TTL_MS) warm()
        return current
    }

    private fun buildSnapshot(createdAtMillis: Long): Snapshot {
        val items = mutableListOf<LocalItem>()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, items)
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED) {
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, items)
        }
        return Snapshot(
            createdAtMillis = createdAtMillis,
            exact = items.groupBy { ExactKey(it.name.normalized(), it.sizeBytes) },
            byName = items.groupBy { it.name.normalized() },
        )
    }

    private fun selectByDate(candidates: List<LocalItem>, dateTakenMillis: Long): LocalItem? {
        if (dateTakenMillis <= 0L) return null
        val matches = candidates.filter {
            it.dateTakenMillis > 0L && abs(it.dateTakenMillis - dateTakenMillis) <= DATE_TOLERANCE_MS
        }
        return matches.singleOrNull()
    }

    private fun queryCollection(collection: Uri, destination: MutableList<LocalItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        try {
            appContext.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.IS_PENDING}=0",
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    val size = cursor.getLong(sizeColumn)
                    if (size <= 0L) continue
                    val id = cursor.getLong(idColumn)
                    destination += LocalItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        sizeBytes = size,
                        dateTakenMillis = cursor.getLong(dateColumn),
                        mimeType = cursor.getString(mimeColumn).orEmpty(),
                    )
                }
            }
        } catch (_: SecurityException) {
            // The user may switch from full to selected-photo access while the provider is alive.
        }
    }

    private fun String.normalized() = lowercase(Locale.ROOT)

    private data class ExactKey(val name: String, val size: Long)

    private data class LocalItem(
        val id: Long,
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val dateTakenMillis: Long,
        val mimeType: String,
    )

    private data class Snapshot(
        val createdAtMillis: Long,
        val exact: Map<ExactKey, List<LocalItem>>,
        val byName: Map<String, List<LocalItem>>,
    )

    private companion object {
        const val CACHE_TTL_MS = 30L * 60L * 1_000L
        const val DATE_TOLERANCE_MS = 2_000L
    }
}
