package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * Which photo picker this phone uses. The newer one (a separate app, stock Pixels) has a People
 * section built from categories; the older one inside MediaProvider (GrapheneOS) shows only albums,
 * so there people become albums (#45). Both call onQueryAlbums, which is why it matters.
 */
internal object PickerKind {
    private val NEWER_PICKERS = setOf("com.google.android.photopicker", "com.android.photopicker")

    @Volatile
    private var newer: Boolean? = null

    /** Read once per process: which picker answers changes only with a system update. */
    fun isNewer(context: Context): Boolean = newer ?: runCatching {
        context.packageManager.resolveActivity(Intent(MediaStore.ACTION_PICK_IMAGES), 0)?.activityInfo?.packageName in NEWER_PICKERS
    }.getOrDefault(false).also { newer = it }
}
