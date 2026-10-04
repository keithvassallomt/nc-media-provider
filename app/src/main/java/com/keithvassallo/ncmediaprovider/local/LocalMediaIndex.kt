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

/**
 * The phone's own photos and videos, read from MediaStore for [LocalMatcher] (#22). The list is
 * cached until MediaStore reports a change, which is also passed on to [onChange] so the matches can
 * be brought up to date.
 */
class LocalMediaIndex(context: Context, private val onChange: () -> Unit = {}) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var cached: List<LocalPhoto>? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = changed()

        override fun onChange(selfChange: Boolean, uri: Uri?) = changed()
    }

    init {
        appContext.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        appContext.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
    }

    fun hasFullAccess(): Boolean = canRead(Manifest.permission.READ_MEDIA_IMAGES) && canRead(Manifest.permission.READ_MEDIA_VIDEO)

    fun hasAnyAccess(): Boolean = canRead(Manifest.permission.READ_MEDIA_IMAGES) || canRead(Manifest.permission.READ_MEDIA_VIDEO)

    /** Drops the cached list, after a permission change for instance. */
    fun invalidate() {
        cached = null
    }

    /** Everything this app may read, or null without any media permission. Call off the main thread. */
    fun photos(): List<LocalPhoto>? {
        if (!hasAnyAccess()) return null
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val photos = ArrayList<LocalPhoto>()
            if (canRead(Manifest.permission.READ_MEDIA_IMAGES)) query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photos)
            if (canRead(Manifest.permission.READ_MEDIA_VIDEO)) query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, photos)
            return photos.also { cached = it }
        }
    }

    private fun changed() {
        invalidate()
        onChange()
    }

    private fun canRead(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun query(collection: Uri, destination: MutableList<LocalPhoto>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        try {
            appContext.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.IS_PENDING}=0", null, null)?.use { cursor ->
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
                    destination += LocalPhoto(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        sizeBytes = size,
                        dateTakenMillis = cursor.getLong(dateColumn),
                        mimeType = cursor.getString(mimeColumn).orEmpty(),
                    )
                }
            }
        } catch (_: SecurityException) {
            // The user may switch from full to selected-photo access while the app is running.
        }
    }
}
