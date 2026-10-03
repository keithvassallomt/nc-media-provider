package com.keithvassallo.ncmediaprovider.data

import com.keithvassallo.ncmediaprovider.data.db.DeletedMedia
import com.keithvassallo.ncmediaprovider.data.db.FolderEtag
import com.keithvassallo.ncmediaprovider.data.db.LibraryDatabase
import com.keithvassallo.ncmediaprovider.data.db.SyncState
import com.keithvassallo.ncmediaprovider.local.CloudPhoto
import com.keithvassallo.ncmediaprovider.local.LocalMatcher
import com.keithvassallo.ncmediaprovider.local.LocalPhoto
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

    /**
     * Rows whose MIME type starts with [mimePrefix], newest taken first, after [after] (the last
     * row of the previous page) for the photo keyboard (PLAN 4.8).
     */
    fun newestPage(mimePrefix: String, after: MediaItem?, limit: Int): List<MediaItem> =
        dao.newestPage(mimePrefix, after?.dateTakenMillis ?: Long.MAX_VALUE, after?.id ?: "", limit)

    fun videosWithoutHeader(limit: Int): List<MediaItem> = dao.videosWithoutHeader(limit)

    fun countTakenSince(since: Long): Int = dao.countTakenSince(since)

    fun monthCounts(): List<MonthCount> = dao.monthCounts()

    /**
     * Stores what video headers said (PLAN 5.3 and 6.0), keyed by row ID: a null header couldn't be
     * read. Missing values are stored as [MediaItem.NOT_IN_HEADER], so they aren't read again while
     * the etag is unchanged; values already read are kept. Changed rows move to the next generation.
     */
    internal fun applyVideoHeaders(headers: Map<String, VideoHeader.Info?>): Boolean = database.runInTransaction<Boolean> {
        val rows = headers.keys.toList().chunked(SQL_BATCH).flatMap(dao::mediaWithIds)
        val memories = memoriesFor(rows)
        val upserts = rows.mapNotNull { row ->
            val header = headers[row.id]
            val item = row.copy(
                durationMillis = row.durationMillis.takeIf { it != 0L } ?: header?.durationMillis ?: MediaItem.NOT_IN_HEADER,
                recordedMillis = row.recordedMillis.takeIf { it != 0L } ?: header?.recordedMillis ?: MediaItem.NOT_IN_HEADER,
            ).resolved(memories[row.id])
            item.takeUnless { it.sameContentAs(row) }
        }
        applyChanges(LibraryChanges(upserts, emptyList()), fullListing = false)
    }

    /** Memories' day counts as last read (PLAN 6.2). */
    fun memoriesDays(): Map<Int, Int> = dao.memoriesDays().associate { it.dayId to it.count }

    /** Days holding a file whose etag moved on since Memories was read. */
    fun staleMemoriesDays(): List<Int> = dao.staleMemoriesDays()

    /**
     * Stores what Memories said of [readDays] (PLAN 6.2), [files] replacing what was stored for them,
     * and resolves every row against the result. [days] is Memories' current day list: whatever was
     * stored for a day no longer in it is dropped. Days not read keep their files and counts, so a
     * read cut short carries on at the next sync. Returns true when any row changed.
     */
    fun applyMemories(days: Map<Int, Int>, readDays: Set<Int>, files: List<MemoriesFile>): Boolean =
        database.runInTransaction<Boolean> {
            val gone = (dao.memoriesFileDays() + dao.memoriesDays().map(MemoriesDay::dayId)).distinct().filterNot(days::containsKey)
            (readDays + gone).toList().chunked(SQL_BATCH).forEach(dao::deleteMemoriesDays)
            files.chunked(SQL_BATCH).forEach(dao::saveMemoriesFiles)
            gone.chunked(SQL_BATCH).forEach(dao::deleteMemoriesDayCounts)
            readDays.mapNotNull { day -> days[day]?.let { MemoriesDay(day, it) } }.chunked(SQL_BATCH).forEach(dao::saveMemoriesDayCounts)
            resolveAll()
        }

    /**
     * Forgets everything Memories said, when it's switched off, gone, or untested, so every row goes
     * back to core and header values (PLAN 6.3). Returns true when any row changed.
     */
    fun clearMemories(): Boolean = database.runInTransaction<Boolean> {
        if (dao.memoriesFileCount() == 0 && dao.memoriesDays().isEmpty()) {
            false
        } else {
            dao.clearMemoriesFiles()
            dao.clearMemoriesDayCounts()
            resolveAll()
        }
    }

    // Albums (PLAN 7.1).

    fun albums(): List<Album> = dao.albums()

    /**
     * Stores the album list as listed: albums no longer listed go with their files, and [relisted]
     * replaces the files of the albums it holds. Returns true when anything the picker shows changed.
     */
    fun saveAlbums(albums: List<Album>, relisted: Map<String, List<AlbumItem>>): Boolean = database.runInTransaction<Boolean> {
        val before = dao.albums()
        val gone = before.map(Album::id) - albums.mapTo(HashSet(), Album::id)
        val itemsBefore = relisted.keys.associateWith { dao.albumItems(it).toSet() }
        gone.chunked(SQL_BATCH).forEach {
            dao.deleteAlbumItems(it)
            dao.deleteAlbums(it)
        }
        dao.saveAlbums(albums)
        relisted.keys.toList().chunked(SQL_BATCH).forEach(dao::deleteAlbumItems)
        relisted.values.flatten().chunked(SQL_BATCH).forEach(dao::saveAlbumItems)
        gone.isNotEmpty() || albums.toSet() != before.toSet() || relisted.any { (id, items) -> items.toSet() != itemsBefore[id] }
    }

    /** Forgets every album, when the server has no Photos app. Returns true when there were any. */
    fun clearAlbums(): Boolean = database.runInTransaction<Boolean> {
        val had = dao.albums().isNotEmpty()
        dao.clearAlbumItems()
        dao.clearAlbums()
        had
    }

    /**
     * Brings the library's album rows in line with the albums (PLAN 7.1). The picker opens an
     * album's photo only if the main sync returned it, so a file that only an album holds (in an
     * album someone shared, say) joins the library under its album path, once however many albums
     * hold it. A file the folders hold keeps its folder row. Returns true when anything changed.
     */
    fun applyAlbumRows(): Boolean = database.runInTransaction<Boolean> {
        val stored = dao.mediaWithHrefLike(Albums.ALBUM_PATH_PATTERN)
        val items = dao.allAlbumItems().groupBy(AlbumItem::id).mapValues { (_, copies) -> copies.minBy(AlbumItem::href) }
        val inFolders = items.keys.toList().chunked(SQL_BATCH).flatMap(dao::mediaWithIds)
            .filterNot { Albums.isAlbumPath(it.href) }
            .mapTo(HashSet(), MediaItem::id)
        val wanted = items.values.filterNot { it.id in inFolders }.map { with(Albums) { it.toMediaItem(generation = 0L) } }
        val memories = memoriesFor(wanted)
        applyChanges(diffLibrary(stored, markLiveVideos(wanted, memories), complete = true, memories::get), fullListing = false)
    }

    /** The albums the picker can show: those with a photo or video it can show. */
    fun pickerAlbums(): List<PickerAlbum> = dao.albums().mapNotNull { Albums.forPicker(it, albumContents(it.id)) }

    /**
     * What the picker shows of an album, a page at a time in file ID order: [afterId] is the last ID
     * of the previous page.
     */
    fun albumPage(albumId: String, afterId: String?, pageSize: Int): Page<MediaItem> {
        val rows = albumContents(albumId).sortedBy(MediaItem::id).filter { afterId == null || it.id > afterId }.take(pageSize)
        return Page(rows, rows.takeIf { it.size == pageSize }?.last()?.id)
    }

    /**
     * The photo keyboard's grid (PLAN 4.8): items of [mimePrefix] older than the keyset position
     * ([date], [id]), newest first, or with [newer] the next newer ones, oldest first.
     */
    fun keyboardPage(source: KeyboardSource, mimePrefix: String, date: Long, id: String, newer: Boolean, limit: Int): List<MediaItem> = when (source) {
        KeyboardSource.Library, KeyboardSource.Favourites -> {
            val favourites = source == KeyboardSource.Favourites
            if (newer) dao.keyboardNewer(mimePrefix, favourites, date, id, limit) else dao.keyboardOlder(mimePrefix, favourites, date, id, limit)
        }
        is KeyboardSource.Person ->
            if (newer) dao.personNewer(source.id, mimePrefix, date, id, limit) else dao.personOlder(source.id, mimePrefix, date, id, limit)
        is KeyboardSource.Album ->
            KeyboardPages.page(albumContents(source.id).filter { it.mimeType.startsWith(mimePrefix) }, date, id, newer, limit)
    }

    /** The years [source] has items of [mimePrefix] from, newest first, for the keyboard's year rail. */
    fun keyboardYears(source: KeyboardSource, mimePrefix: String, zone: java.time.ZoneId): List<Int> = when (source) {
        KeyboardSource.Library -> dao.keyboardYears(mimePrefix, false)
        KeyboardSource.Favourites -> dao.keyboardYears(mimePrefix, true)
        is KeyboardSource.Person -> dao.personYears(source.id, mimePrefix)
        is KeyboardSource.Album -> KeyboardPages.years(albumContents(source.id).filter { it.mimeType.startsWith(mimePrefix) }, zone)
    }

    /** A file that only an album holds, as a row, so it can be opened. */
    fun albumOnlyItem(id: String): MediaItem? = dao.albumItem(id)?.let { with(Albums) { it.toMediaItem(state().generation) } }

    private fun albumContents(albumId: String): List<MediaItem> {
        val items = dao.albumItems(albumId)
        val rows = items.map(AlbumItem::id).chunked(SQL_BATCH).flatMap(dao::mediaWithIds).associateBy(MediaItem::id)
        return Albums.contents(items, rows, state().generation)
    }

    // People (PLAN 7.3).

    fun persons(): List<Person> = dao.persons()

    fun person(id: String): Person? = dao.person(id)

    /**
     * Stores the people as listed: people no longer listed go with their photos, and [relisted]
     * replaces the photos of the people it holds. Returns true when anything changed.
     */
    fun savePeople(people: List<Person>, relisted: Map<String, List<String>>): Boolean = database.runInTransaction<Boolean> {
        val before = dao.persons()
        val gone = before.map(Person::id) - people.mapTo(HashSet(), Person::id)
        gone.chunked(SQL_BATCH).forEach {
            dao.deletePersonItems(it)
            dao.deletePersons(it)
        }
        people.chunked(SQL_BATCH).forEach(dao::savePersons)
        relisted.keys.toList().chunked(SQL_BATCH).forEach(dao::deletePersonItems)
        relisted.flatMap { (person, ids) -> ids.map { PersonItem(person, it) } }.chunked(SQL_BATCH).forEach(dao::savePersonItems)
        gone.isNotEmpty() || people.toSet() != before.toSet() || relisted.isNotEmpty()
    }

    fun clearPeople(): Boolean = database.runInTransaction<Boolean> {
        val had = dao.persons().isNotEmpty()
        dao.clearPersonItems()
        dao.clearPersons()
        had
    }

    /** The people the picker can show: those with a photo in the library. */
    fun pickerPeople(): List<PickerPerson> = People.forPicker(dao.personSummaries())

    /** A page of one person's photos; [afterId] is the last ID of the previous page. */
    fun personPage(personId: String, afterId: String?, pageSize: Int): Page<MediaItem> {
        val rows = dao.personMedia(personId, afterId.orEmpty(), pageSize)
        return Page(rows, rows.takeIf { it.size == pageSize }?.last()?.id)
    }

    /** Rows whose values come from Memories, and live-photo videos it paired (PLAN 6.2). */
    fun memoriesStats(): Pair<Int, Int> = dao.memoriesMatchedCount() to dao.liveVideoCount()

    /** Rows with a copy on the phone (PLAN 3.2). */
    fun matchedCount(): Int = dao.matchedCount()

    fun media(id: String): MediaItem? = dao.media(id)

    /**
     * Starts over for a different server, user or folder set: a new instance ID gives a new
     * collection ID, so MediaProvider drops what it had.
     */
    fun resetFor(sourceKey: String) = database.runInTransaction(Runnable {
        dao.clearMedia()
        dao.clearDeleted()
        dao.clearFolders()
        dao.clearMemoriesFiles()
        dao.clearMemoriesDayCounts()
        dao.clearAlbumItems()
        dao.clearAlbums()
        dao.clearPersonItems()
        dao.clearPersons()
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
        val memories = memoriesFor(listed)
        // Files that only an album holds aren't the folders' to delete (see applyAlbumRows); one the
        // folders now hold too moves to its folder row.
        val listedIds = listed.mapTo(HashSet(), MediaItem::id)
        val stored = dao.allMedia().filter { !Albums.isAlbumPath(it.href) || it.id in listedIds }
        applyChanges(diffLibrary(stored, markLiveVideos(listed, memories), complete, memories::get), fullListing = complete)
    }

    /**
     * Commits the direct listings of the folders whose etag changed, and drops what was in folders
     * that are gone (PLAN 2.4). Only rows in those folders are compared, so a file missing from them
     * is deleted unless it turned up in another of them, which makes it a move.
     *
     * A listed file whose row is in a folder that wasn't re-listed is still in that folder too, since
     * that folder's etag didn't move: the file is reachable at two paths (PLAN 2.2). It keeps one row,
     * under the alphabetically first path as in [Listing], and the library is marked as having such
     * files.
     */
    fun commitFolders(listings: Map<String, List<MediaItem>>, removedFolders: Set<String>): Boolean =
        database.runInTransaction<Boolean> {
            val scope = (listings.keys + removedFolders).toList()
            val stored = scope.chunked(SQL_BATCH).flatMap(dao::mediaIn)
            val storedIds = stored.mapTo(HashSet(), MediaItem::id)
            val listed = listings.values.flatten()
            val elsewhere = listed.map(MediaItem::id).filterNot(storedIds::contains).distinct()
                .chunked(SQL_BATCH).flatMap(dao::mediaWithIds)
            val copies = (listed + elsewhere).groupBy(MediaItem::id).values
            // An album row isn't a second path in the folders: the folder row replaces it.
            if (copies.any { copy -> copy.filterNot { Albums.isAlbumPath(it.href) }.distinctBy(MediaItem::href).size > 1 }) {
                val state = dao.state() ?: state()
                dao.saveState(state.copy(duplicatePaths = true).also { cached = it })
            }
            val kept = copies.map { copy -> copy.filterNot { Albums.isAlbumPath(it.href) }.minByOrNull(MediaItem::href) ?: copy.minBy(MediaItem::href) }
            // A live photo's two halves share a folder, so each folder's listing holds both.
            val memories = memoriesFor(kept)
            applyChanges(diffLibrary(stored + elsewhere, markLiveVideos(kept, memories), complete = true, memories::get), fullListing = false)
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

    /**
     * The rows [LocalMatcher] could pair with any of [local], plus every row matched before (PLAN
     * 3.2). Every rule needs the same size or the same name, so SQLite skips the rest: reading and
     * checking all 16,895 rows of Keith's library took 8 s on a dozing phone.
     */
    fun matchCandidates(local: Collection<LocalPhoto>): List<CloudPhoto> {
        val sizes = local.map(LocalPhoto::sizeBytes).distinct().chunked(MATCH_BATCH)
        val names = local.map { LocalMatcher.nameKey(it.name) }.distinct().chunked(MATCH_BATCH)
        return (0 until maxOf(sizes.size, names.size, 1))
            .flatMap { dao.matchCandidates(sizes.getOrElse(it) { emptyList() }, names.getOrElse(it) { emptyList() }) }
            .distinctBy(CloudPhoto::id)
    }

    /**
     * Stores the phone copies [LocalMatcher] found, keyed by row ID; rows missing from [matches]
     * lose any match. MediaProvider keeps rows as they were sent, so each changed row moves to the
     * next generation. Returns how many rows changed.
     */
    fun applyLocalMatches(matches: Map<String, String>): Int = database.runInTransaction<Int> {
        val current = dao.localMatches().associate { it.id to it.mediaStoreUri }
        val changedIds = (current.keys + matches.keys).filter { current[it] != matches[it] }
        val upserts = changedIds.chunked(SQL_BATCH).flatMap(dao::mediaWithIds).map { it.copy(mediaStoreUri = matches[it.id]) }
        applyChanges(LibraryChanges(upserts, emptyList()), fullListing = false)
        upserts.size
    }

    /** Folders hidden by a marker file at the last check, by [folderKey]. */
    fun hiddenFolders(): Set<String> = state().hiddenFolders.split('\n').filterTo(HashSet(), String::isNotEmpty)

    /**
     * Saves what the next change check compares against (PLAN 2.4). Only a full listing knows
     * [respectsNoMedia], [duplicatePaths] and [listingVersion]; a change check leaves them as they are.
     */
    fun saveFolderState(
        rootEtag: String,
        folders: Map<String, String>,
        hiddenFolders: Set<String>,
        respectsNoMedia: Boolean? = null,
        duplicatePaths: Boolean? = null,
        listingVersion: Int? = null,
    ) = database.runInTransaction(Runnable {
        dao.clearFolders()
        folders.map { (path, etag) -> FolderEtag(path, etag) }.chunked(SQL_BATCH).forEach(dao::saveFolders)
        val state = dao.state() ?: state()
        val updated = state.copy(
            rootEtag = rootEtag,
            hiddenFolders = hiddenFolders.sorted().joinToString("\n"),
            respectsNoMedia = respectsNoMedia ?: state.respectsNoMedia,
            duplicatePaths = duplicatePaths ?: state.duplicatePaths,
            listingVersion = listingVersion ?: state.listingVersion,
        )
        dao.saveState(updated.also { cached = it })
    })

    /** Records that a sync finished, whatever it found. */
    fun markChecked() = database.runInTransaction(Runnable {
        val state = dao.state() ?: state()
        dao.saveState(state.copy(lastCheckMillis = nowMillis()).also { cached = it })
    })

    /** What Memories said of [items], by ID. Call inside a transaction. */
    private fun memoriesFor(items: Collection<MediaItem>): Map<String, MemoriesFile> =
        items.map(MediaItem::id).chunked(SQL_BATCH).flatMap(dao::memoriesWithIds).associateBy(MemoriesFile::id)

    /** [items] with [MediaItem.isLiveVideo] set by Memories' live-photo pairs among them. */
    private fun markLiveVideos(items: Collection<MediaItem>, memories: Map<String, MemoriesFile>): List<MediaItem> {
        val halves = MemoriesApi.liveHalves(items, memories::get)
        return items.map { it.copy(isLiveVideo = it.id in halves) }
    }

    /** Resolves every row against what Memories said and stores the rows that changed. Call inside a transaction. */
    private fun resolveAll(): Boolean {
        val memories = dao.memoriesFiles().associateBy(MemoriesFile::id)
        val rows = dao.allMedia()
        val halves = MemoriesApi.liveHalves(rows, memories::get)
        val upserts = rows.mapNotNull { row ->
            row.resolved(memories[row.id]).copy(isLiveVideo = row.id in halves).takeUnless { it.sameContentAs(row) }
        }
        return applyChanges(LibraryChanges(upserts, emptyList()), fullListing = false)
    }

    /**
     * Writes [changes] under the next generation, if there are any. A live-photo video is written
     * like any row but journalled as deleted, which is how the picker learns to drop it (PLAN 6.2).
     * Call inside a transaction.
     */
    private fun applyChanges(changes: LibraryChanges, fullListing: Boolean): Boolean {
        val state = dao.state() ?: state()
        val now = nowMillis()
        val generation = if (changes.isEmpty) state.generation else state.generation + 1
        if (!changes.isEmpty) {
            changes.upserts.map { it.copy(generation = generation) }.chunked(SQL_BATCH).forEach(dao::upsertMedia)
            val (hidden, shown) = changes.upserts.partition(MediaItem::isLiveVideo)
            shown.map(MediaItem::id).chunked(SQL_BATCH).forEach(dao::forgetDeleted)
            hidden.chunked(SQL_BATCH).forEach { rows -> dao.upsertDeleted(rows.map { DeletedMedia(it.id, generation, now) }) }
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

        /** Sizes and names each, so one statement binds at most 800 parameters. */
        const val MATCH_BATCH = 400
    }
}
