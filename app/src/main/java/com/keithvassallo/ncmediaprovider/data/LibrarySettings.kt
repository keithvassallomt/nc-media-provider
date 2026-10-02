package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import androidx.core.content.edit

/** Plain preferences: cheap enough to read on the picker's 100 ms collection-info path. */
class LibrarySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * The library folders, as [normalizeFolders] returns them. Changing them starts a new library
     * (PLAN 2.7). The folder picker arrives in 4.3; until then debug builds set them.
     */
    var folders: List<String>
        get() {
            // Before several folders, the one folder was stored under its own key.
            val stored = preferences.getString(KEY_FOLDERS, null) ?: preferences.getString(KEY_FOLDER, null) ?: "/"
            return normalizeFolders(stored.split(FOLDER_SEPARATOR))
        }
        set(value) = preferences.edit {
            putString(KEY_FOLDERS, normalizeFolders(value).joinToString(FOLDER_SEPARATOR.toString()))
            remove(KEY_FOLDER)
        }

    /**
     * Hide folders holding a `.nomedia` file or another of [SearchRequest.HIDING_MARKERS], as Nextcloud
     * Photos and Memories do. A change takes effect at the next sync, which then lists everything.
     */
    var respectNoMedia: Boolean
        get() = preferences.getBoolean(KEY_RESPECT_NO_MEDIA, true)
        set(value) = preferences.edit { putBoolean(KEY_RESPECT_NO_MEDIA, value) }

    var debugSeed: String?
        get() = preferences.getString(KEY_DEBUG_SEED, null)
        set(value) = preferences.edit { putString(KEY_DEBUG_SEED, value) }

    companion object {
        private const val PREFERENCES = "library"
        private const val KEY_FOLDER = "folder"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_RESPECT_NO_MEDIA = "respect_no_media"
        private const val KEY_DEBUG_SEED = "debug_seed"

        /** Nextcloud refuses control characters in file names, so no folder path holds one. */
        private const val FOLDER_SEPARATOR = '\n'

        /** "Photos/", "/Photos/" and "/Photos" are the same folder; the root is "/". */
        fun normalizeFolder(value: String): String {
            val trimmed = value.trim().trim('/')
            return if (trimmed.isEmpty()) "/" else "/$trimmed"
        }

        /**
         * Normalised, sorted and without repeats. A folder inside another selected folder is dropped,
         * since the outer one already covers it. No folders at all means the whole account.
         */
        fun normalizeFolders(values: Collection<String>): List<String> {
            val folders = values.filter(String::isNotBlank).map(::normalizeFolder).distinct().sorted()
            return folders
                .filter { folder -> folders.none { other -> other != folder && (other == "/" || folder.startsWith("$other/")) } }
                .ifEmpty { listOf("/") }
        }
    }
}
