package com.keithvassallo.ncmediaprovider.data

import com.keithvassallo.ncmediaprovider.data.db.DeletedMedia
import com.keithvassallo.ncmediaprovider.data.db.FolderEtag
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import com.keithvassallo.ncmediaprovider.data.db.SyncState
import java.util.UUID

/**
 * The library as the picker sees it (PLAN 2.1 and 2.3). Every change is committed in one
 * transaction under the next generation; the picker reads rows by generation, so it can always ask
 * "what changed since N?".
 */
class LibraryStore(
    private val database: LibraryDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.dao()

    @Volatile
    private var cached: SyncState? = null

    /** The current state, from memory after the first read. */
    fun state(): SyncState = cached ?: database.runInTransaction<SyncState> {
        dao.state() ?: SyncState(instanceId = UUID.randomUUID().toString()).also(dao::saveState)
    }.also { cached = it }

    fun mediaCount(): Int = dao.mediaCount()

    fun media(id: String): MediaItem? = dao.media(id)

    /**
     * Starts over for a different server, user or folder set: a new instance ID gives a new
     * collection ID, so MediaProvider drops what it had.
     */
    fun resetFor(sourceKey: String) = database.runInTransaction(Runnable {
        dao.clearMedia()
        dao.clearDeleted()
        dao.clearFolders()
        val fresh = SyncState(instanceId = UUID.randomUUID().toString(), sourceKey = sourceKey)
        dao.saveState(fresh)
        cached = fresh
    })

    /**
     * Commits a listing and returns true when it changed anything, in which case the generation
     * moved and the picker must be told. A [complete] listing also journals every stored file it no
     * longer contains as deleted.
     */
    fun commit(listed: Collection<MediaItem>, complete: Boolean): Boolean = database.runInTransaction<Boolean> {
        applyChanges(diffLibrary(dao.allMedia(), listed, complete), fullListing = complete)
    }

    /**
     * Commits the direct listings of the folders whose etag changed, and drops what was in folders
     * that are gone (PLAN 2.4). Only rows in those folders are compared, so a file missing from them
     * is deleted unless it turned up in another of them, which makes it a move.
     */
    fun commitFolders(listings: Map<String, List<MediaItem>>, removedFolders: Set<String>): Boolean =
        database.runInTransaction<Boolean> {
            val scope = (listings.keys + removedFolders).toList()
            val stored = scope.chunked(SQL_BATCH).flatMap(dao::mediaIn)
            applyChanges(diffLibrary(stored, listings.values.flatten(), complete = true), fullListing = false)
        }

    /**
     * Brings favourite flags in line with [favoriteIds]. Favouriting changes no etag, so the folder
     * walk can't see it; this is the cheap separate check.
     */
    fun applyFavorites(favoriteIds: Set<String>): Boolean = database.runInTransaction<Boolean> {
        val current = dao.favoriteIds().toSet()
        val flipped = (favoriteIds - current) + (current - favoriteIds)
        val upserts = flipped.toList().chunked(SQL_BATCH)
            .flatMap(dao::mediaWithIds)
            .map { it.copy(isFavorite = it.id in favoriteIds) }
        applyChanges(LibraryChanges(upserts, emptyList()), fullListing = false)
    }

    /** Etags at the last check, by [folderKey]. */
    fun folderEtags(): Map<String, String> = dao.folders().associate { it.path to it.etag }

    fun saveFolderEtags(rootEtag: String, folders: Map<String, String>) = database.runInTransaction(Runnable {
        dao.clearFolders()
        folders.map { (path, etag) -> FolderEtag(path, etag) }.chunked(SQL_BATCH).forEach(dao::saveFolders)
        val state = dao.state() ?: state()
        dao.saveState(state.copy(rootEtag = rootEtag).also { cached = it })
    })

    /** Writes [changes] under the next generation, if there are any. Call inside a transaction. */
    private fun applyChanges(changes: LibraryChanges, fullListing: Boolean): Boolean {
        val state = dao.state() ?: state()
        val now = nowMillis()
        val generation = if (changes.isEmpty) state.generation else state.generation + 1
        if (!changes.isEmpty) {
            changes.upserts.map { it.copy(generation = generation) }.chunked(SQL_BATCH).forEach(dao::upsertMedia)
            changes.upserts.map(MediaItem::id).chunked(SQL_BATCH).forEach(dao::forgetDeleted)
            changes.deletedIds.chunked(SQL_BATCH).forEach { ids ->
                dao.deleteMedia(ids)
                dao.upsertDeleted(ids.map { DeletedMedia(it, generation, now) })
            }
        }
        val updated = state.copy(
            generation = generation,
            imported = state.imported || fullListing,
            lastFullListingMillis = if (fullListing) now else state.lastFullListingMillis,
        )
        dao.saveState(updated)
        cached = updated
        return !changes.isEmpty
    }

    /**
     * Rows changed after [sinceGeneration] (all rows when null), oldest change first. The first page
     * pins the current generation as the top of the pass; later pages carry it in their token.
     */
    fun mediaPage(sinceGeneration: Long?, pageToken: String?, pageSize: Int): Page<MediaItem> {
        val token = PageToken.decode(pageToken, PageToken.MEDIA)
        val top = token?.top ?: state().generation
        val rows = dao.mediaPage(sinceGeneration ?: -1L, top, token?.afterGeneration ?: -1L, token?.afterId.orEmpty(), pageSize)
        val next = rows.takeIf { it.size == pageSize }?.last()
            ?.let { PageToken(PageToken.MEDIA, top, it.generation, it.id).encode() }
        return Page(rows, next)
    }

    /** Files deleted after [sinceGeneration], with the same paging rules as [mediaPage]. */
    fun deletedPage(sinceGeneration: Long, pageToken: String?, pageSize: Int): Page<String> {
        val token = PageToken.decode(pageToken, PageToken.DELETED)
        val top = token?.top ?: state().generation
        val rows = dao.deletedPage(sinceGeneration, top, token?.afterGeneration ?: -1L, token?.afterId.orEmpty(), pageSize)
        val next = rows.takeIf { it.size == pageSize }?.last()
            ?.let { PageToken(PageToken.DELETED, top, it.generation, it.id).encode() }
        return Page(rows.map(DeletedMedia::id), next)
    }

    /**
     * True when a picker that last synced at [sinceGeneration] can no longer be answered
     * completely, because journal rows it needs were pruned. The caller then forces a full rebuild.
     */
    fun isBehindDeletionFloor(sinceGeneration: Long): Boolean = sinceGeneration in 0 until state().deletionFloor

    /**
     * Drops journal rows older than [maxAgeMillis] and records the newest dropped generation as the
     * floor. The base app kept only 8 generations and silently lost older deletions (PLAN 2.3).
     */
    fun pruneDeletions(maxAgeMillis: Long) = database.runInTransaction(Runnable {
        val through = dao.newestDeletionBefore(nowMillis() - maxAgeMillis) ?: return@Runnable
        dao.pruneDeletedThrough(through)
        val state = dao.state() ?: state()
        dao.saveState(state.copy(deletionFloor = maxOf(state.deletionFloor, through)).also { cached = it })
    })

    /** Forces MediaProvider to rebuild: a new epoch gives a new collection ID. */
    fun bumpEpoch() = database.runInTransaction(Runnable {
        val state = dao.state() ?: state()
        dao.saveState(state.copy(epoch = state.epoch + 1).also { cached = it })
    })

    private companion object {
        /** Stays under SQLite's limit on bound parameters per statement. */
        const val SQL_BATCH = 500
    }
}
