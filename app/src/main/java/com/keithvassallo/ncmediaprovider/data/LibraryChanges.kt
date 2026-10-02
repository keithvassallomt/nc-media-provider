package com.keithvassallo.ncmediaprovider.data

/** What a listing changes in the stored library. */
internal data class LibraryChanges(
    /** New rows and rows whose content changed (any field but the generation). */
    val upserts: List<MediaItem>,
    val deletedIds: List<String>,
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletedIds.isEmpty()
}

/**
 * Compares a listing with the stored rows. Only a [complete] listing can prove a file is gone: a
 * partial one (a first-import page, say) never deletes anything.
 */
internal fun diffLibrary(stored: Collection<MediaItem>, listed: Collection<MediaItem>, complete: Boolean): LibraryChanges {
    val storedById = stored.associateBy(MediaItem::id)
    val upserts = listed.filter { item ->
        val old = storedById[item.id]
        old == null || old.copy(generation = 0L) != item.copy(generation = 0L)
    }
    val deletedIds = if (complete) {
        val listedIds = listed.mapTo(HashSet(), MediaItem::id)
        stored.filter { it.id !in listedIds }.map(MediaItem::id)
    } else {
        emptyList()
    }
    return LibraryChanges(upserts, deletedIds)
}

/**
 * A position in one sync pass (PLAN 2.3). [pass] says which query issued it: Android 17's picker can
 * pass the last media token into the deletions query, so a token from the other pass means "start".
 * [top] pins the newest generation the pass may see, so a commit in the middle of a sync can't mix
 * two versions of the library. The rest is a keyset position: rows sort by (generation, id).
 */
internal data class PageToken(
    val pass: Char,
    val top: Long,
    val afterGeneration: Long,
    val afterId: String,
) {
    fun encode(): String = "$pass:$top:$afterGeneration:$afterId"

    companion object {
        const val MEDIA = 'm'
        const val DELETED = 'd'

        fun decode(token: String?, pass: Char): PageToken? {
            val parts = token?.split(':', limit = 4) ?: return null
            if (parts.size != 4 || parts[0] != pass.toString()) return null
            val top = parts[1].toLongOrNull() ?: return null
            val afterGeneration = parts[2].toLongOrNull() ?: return null
            return PageToken(pass, top, afterGeneration, parts[3])
        }
    }
}
