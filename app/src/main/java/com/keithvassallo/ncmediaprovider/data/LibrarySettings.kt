package com.keithvassallo.ncmediaprovider.data

import android.content.Context
import androidx.core.content.edit

/** Plain preferences: cheap enough to read on the picker's 100 ms collection-info path. */
class LibrarySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * The library folders, as [normalizeFolders] returns them. Changing them starts a new library
     * (PLAN 2.7). Chosen in the folder picker (PLAN 4.3); see [foldersChosen].
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

    /** False until the user picks folders; nothing syncs before then. */
    val foldersChosen: Boolean get() = preferences.contains(KEY_FOLDERS) || preferences.contains(KEY_FOLDER)

    /** Forgets the folders, after signing in as someone else or signing out. */
    fun clearFolders() = preferences.edit {
        remove(KEY_FOLDERS)
        remove(KEY_FOLDER)
    }

    /**
     * Hide folders holding a `.nomedia` file or another of [SearchRequest.HIDING_MARKERS], as Nextcloud
     * Photos and Memories do. A change takes effect at the next sync, which then lists everything.
     */
    var respectNoMedia: Boolean
        get() = preferences.getBoolean(KEY_RESPECT_NO_MEDIA, true)
        set(value) = preferences.edit { putBoolean(KEY_RESPECT_NO_MEDIA, value) }

    /**
     * Take dates, sizes and live-photo pairs from Memories when the server has a tested version
     * (PLAN 6.1). Turning it off puts back the core values at the next sync.
     */
    var useMemories: Boolean
        get() = preferences.getBoolean(KEY_USE_MEMORIES, true)
        set(value) = preferences.edit { putBoolean(KEY_USE_MEMORIES, value) }

    /** Whether the last sync found people to show (PLAN 7.3); read on the picker's capabilities call. */
    var peopleAvailable: Boolean
        get() = preferences.getBoolean(KEY_PEOPLE_AVAILABLE, false)
        set(value) = preferences.edit { putBoolean(KEY_PEOPLE_AVAILABLE, value) }

    /** The Memories version the last sync found: empty when there was none, null before any check. */
    var memoriesVersion: String?
        get() = preferences.getString(KEY_MEMORIES_VERSION, null)
        set(value) = preferences.edit { putString(KEY_MEMORIES_VERSION, value) }

    /**
     * Whether this app was the picker's selected cloud provider when last seen, so an update that
     * deselects it can select it again (PLAN 4.6).
     */
    var wasSelectedProvider: Boolean
        get() = preferences.getBoolean(KEY_WAS_SELECTED, false)
        set(value) = preferences.edit { putBoolean(KEY_WAS_SELECTED, value) }

    /** When this app was last seen selected; see [LibraryRepository.noteSelectedProvider]. */
    var selectedSeenMillis: Long
        get() = preferences.getLong(KEY_SELECTED_SEEN, 0L)
        set(value) = preferences.edit { putLong(KEY_SELECTED_SEEN, value) }

    /** Download thumbnails ahead of time (PLAN 5.6). */
    var precacheEnabled: Boolean
        get() = preferences.getBoolean(KEY_PRECACHE, false)
        set(value) = preferences.edit { putBoolean(KEY_PRECACHE, value) }

    /** How far back the pre-cache goes, in months; 0 for everything. Applies when [precacheBytes] is 0. */
    var precacheMonths: Int
        get() = preferences.getInt(KEY_PRECACHE_MONTHS, 0)
        set(value) = preferences.edit { putInt(KEY_PRECACHE_MONTHS, value) }

    /** How much the pre-cache may take, newest first, when chosen by size; 0 when chosen by date. */
    var precacheBytes: Long
        get() = preferences.getLong(KEY_PRECACHE_BYTES, 0L)
        set(value) = preferences.edit { putLong(KEY_PRECACHE_BYTES, value) }

    /** The thumbnail size the pre-cache stores: what this phone's picker grid asks for (PLAN 5.6). */
    var precacheSizePx: Int
        get() = preferences.getInt(KEY_PRECACHE_SIZE, PreviewSizes.SMALL_PX)
        set(value) = preferences.edit { putInt(KEY_PRECACHE_SIZE, value) }

    /** How much space downloaded originals may take (PLAN 8.2); the oldest-used go first. */
    var originalsCacheBytes: Long
        get() = preferences.getLong(KEY_ORIGINALS_CACHE, MediaDiskCache.Area.ORIGINAL.maximumBytes)
        set(value) = preferences.edit { putLong(KEY_ORIGINALS_CACHE, value) }

    /** The last pre-cache run's result, "ready of total", for the setup screen. */
    var precacheReady: Pair<Int, Int>
        get() = preferences.getInt(KEY_PRECACHE_READY, 0) to preferences.getInt(KEY_PRECACHE_TOTAL, 0)
        set(value) = preferences.edit {
            putInt(KEY_PRECACHE_READY, value.first)
            putInt(KEY_PRECACHE_TOTAL, value.second)
        }

    /** Why the last sync failed, for the diagnostics (PLAN 4.6); null after a sync succeeds. */
    var lastSyncError: String?
        get() = preferences.getString(KEY_LAST_SYNC_ERROR, null)
        set(value) = preferences.edit { putString(KEY_LAST_SYNC_ERROR, value) }

    companion object {
        private const val PREFERENCES = "library"
        private const val KEY_FOLDER = "folder"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_RESPECT_NO_MEDIA = "respect_no_media"
        private const val KEY_USE_MEMORIES = "use_memories"
        private const val KEY_MEMORIES_VERSION = "memories_version"
        private const val KEY_PEOPLE_AVAILABLE = "people_available"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        private const val KEY_PRECACHE = "precache"
        private const val KEY_PRECACHE_MONTHS = "precache_months"
        private const val KEY_PRECACHE_READY = "precache_ready"
        private const val KEY_PRECACHE_TOTAL = "precache_total"
        private const val KEY_PRECACHE_SIZE = "precache_size_px"
        private const val KEY_PRECACHE_BYTES = "precache_bytes"
        private const val KEY_ORIGINALS_CACHE = "originals_cache_bytes"
        private const val KEY_WAS_SELECTED = "was_selected_provider"
        private const val KEY_SELECTED_SEEN = "selected_seen_millis"

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
