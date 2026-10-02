package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import androidx.core.content.edit

/** Plain preferences: cheap enough to read on the picker's 100 ms collection-info path. */
class LibrarySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The one library folder of the proof of concept (PLAN 1.3); several folders arrive in 4.3. */
    var folder: String
        get() = preferences.getString(KEY_FOLDER, "/") ?: "/"
        set(value) = preferences.edit { putString(KEY_FOLDER, normalizeFolder(value)) }

    /** Part of the collection ID: bumping it makes MediaProvider rebuild the library from scratch. */
    val epoch: Long get() = preferences.getLong(KEY_EPOCH, 0L)

    /**
     * The last generation reported to the picker. It survives process death: an in-memory counter
     * would restart at 0 with the same collection ID, which MediaProvider reads as going backwards.
     */
    val generation: Long get() = preferences.getLong(KEY_GENERATION, 0L)

    /** Fingerprint of the listing that [generation] describes. */
    val fingerprint: String? get() = preferences.getString(KEY_FINGERPRINT, null)

    var debugSeed: String?
        get() = preferences.getString(KEY_DEBUG_SEED, null)
        set(value) = preferences.edit { putString(KEY_DEBUG_SEED, value) }

    fun recordListing(generation: Long, fingerprint: String) = preferences.edit(commit = true) {
        putLong(KEY_GENERATION, generation)
        putString(KEY_FINGERPRINT, fingerprint)
    }

    companion object {
        private const val PREFERENCES = "library"
        private const val KEY_FOLDER = "folder"
        private const val KEY_EPOCH = "epoch"
        private const val KEY_GENERATION = "generation"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_DEBUG_SEED = "debug_seed"

        /** "Photos/", "/Photos/" and "/Photos" are the same folder; the root is "/". */
        fun normalizeFolder(value: String): String {
            val trimmed = value.trim().trim('/')
            return if (trimmed.isEmpty()) "/" else "/$trimmed"
        }
    }
}
