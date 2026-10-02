package com.keithvassallo.ncmediaprovider.data

/** What a listing changes in the stored library. */
internal data class LibraryChanges(
    /** New rows and rows whose content changed (any field but the generation and the local match). */
    val upserts: List<MediaItem>,
    val deletedIds: List<String>,
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deletedIds.isEmpty()
}

/**
 * Compares a listing with the stored rows. Only a [complete] listing can prove a file is gone: a
 * partial one (a first-import page, say) never deletes anything. Listings know nothing of what the
 * phone works out itself: a row keeps its local match until matching runs again, and a video its
 * duration while its etag is unchanged (PLAN 5.3).
 */
internal fun diffLibrary(stored: Collection<MediaItem>, listed: Collection<MediaItem>, complete: Boolean): LibraryChanges {
    val storedById = stored.associateBy(MediaItem::id)
    val upserts = listed.mapNotNull { listedItem ->
        val old = storedById[listedItem.id] ?: return@mapNotNull listedItem
        val item = listedItem.copy(
            mediaStoreUri = old.mediaStoreUri,
            durationMillis = if (old.etag == listedItem.etag) old.durationMillis else listedItem.durationMillis,
        )
        if (old.sameContentAs(item)) null else item
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
 * Equal in everything but the generation and the local match. Compared field by field: no copies
 * for 20,000 rows.
 */
internal fun MediaItem.sameContentAs(other: MediaItem): Boolean =
    id == other.id && href == other.href && etag == other.etag && fileName == other.fileName &&
        mimeType == other.mimeType && sizeBytes == other.sizeBytes && lastModifiedMillis == other.lastModifiedMillis &&
        dateTakenMillis == other.dateTakenMillis && durationMillis == other.durationMillis && width == other.width &&
        height == other.height && isFavorite == other.isFavorite && folder == other.folder

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
