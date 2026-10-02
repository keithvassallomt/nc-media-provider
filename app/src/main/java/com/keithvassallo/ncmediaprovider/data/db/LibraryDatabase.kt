package com.keithvassallo.ncmediaprovider.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.local.CloudPhoto

/** A file removed from the library, and the generation that removed it (the deletion journal). */
@Entity(tableName = "deleted", indices = [Index(value = ["generation", "id"])])
data class DeletedMedia(
    @PrimaryKey val id: String,
    val generation: Long,
    val deletedAtMillis: Long,
)

/** A row's match to the phone's own copy (PLAN 3.2). */
data class LocalMatch(val id: String, val mediaStoreUri: String)

/** The etag a folder had at the last check (PLAN 2.4); keyed by its decoded path ending in '/'. */
@Entity(tableName = "folder")
data class FolderEtag(
    @PrimaryKey val path: String,
    val etag: String,
)

/** The one row describing what the picker has been told. */
@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val key: Int = 0,
    /** Random per database: if the database is lost, the collection ID changes with it. */
    val instanceId: String,
    val generation: Long = 0L,
    /** Bumped to make MediaProvider rebuild from scratch (part of the collection ID). */
    val epoch: Long = 0L,
    /** Which server, user and folders this library holds; a change starts a new library. */
    val sourceKey: String = "",
    /** Journal rows at or below this generation were pruned (PLAN 2.3). */
    val deletionFloor: Long = 0L,
    val lastFullListingMillis: Long = 0L,
    /** True once one complete listing has been committed. */
    val imported: Boolean = false,
    /**
     * The library folders' etags at the last check, joined by spaces: unchanged means nothing below
     * them changed.
     */
    val rootEtag: String = "",
    /** Folders hidden by a marker such as `.nomedia` at the last check, as folder keys joined by '\n'. */
    @ColumnInfo(defaultValue = "")
    val hiddenFolders: String = "",
    /** Whether the last full listing hid those folders; listings before schema 3 didn't. */
    @ColumnInfo(defaultValue = "0")
    val respectsNoMedia: Boolean = false,
    /** Some file is reachable at two paths, which makes removed folders need a full listing (PLAN 2.2). */
    @ColumnInfo(defaultValue = "0")
    val duplicatePaths: Boolean = false,
    /** When the last sync finished, whether or not it found anything. */
    @ColumnInfo(defaultValue = "0")
    val lastCheckMillis: Long = 0L,
    /** What the last full listing included; see LibraryRepository's LISTING_VERSION. */
    @ColumnInfo(defaultValue = "0")
    val listingVersion: Int = 0,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM sync_state WHERE `key` = 0")
    fun state(): SyncState?

    @Upsert
    fun saveState(state: SyncState)

    @Query("SELECT * FROM media")
    fun allMedia(): List<MediaItem>

    @Query("SELECT * FROM media WHERE id = :id")
    fun media(id: String): MediaItem?

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    fun mediaWithIds(ids: List<String>): List<MediaItem>

    @Query("SELECT * FROM media WHERE folder IN (:folders)")
    fun mediaIn(folders: List<String>): List<MediaItem>

    @Query("SELECT id FROM media WHERE isFavorite = 1")
    fun favoriteIds(): List<String>

    /** Rows that could match a phone item with one of [sizes] or [names], plus every matched row. */
    @Query(
        "SELECT id, fileName, sizeBytes, dateTakenMillis, mimeType, mediaStoreUri FROM media " +
            "WHERE sizeBytes IN (:sizes) OR lower(fileName) IN (:names) OR mediaStoreUri IS NOT NULL",
    )
    fun matchCandidates(sizes: List<Long>, names: List<String>): List<CloudPhoto>

    @Query("SELECT id, mediaStoreUri FROM media WHERE mediaStoreUri IS NOT NULL")
    fun localMatches(): List<LocalMatch>

    @Query("SELECT COUNT(*) FROM media WHERE mediaStoreUri IS NOT NULL")
    fun matchedCount(): Int

    /** Videos whose duration hasn't been read yet (0), newest first (PLAN 5.3). */
    @Query("SELECT * FROM media WHERE mimeType LIKE 'video/%' AND durationMillis = 0 ORDER BY dateTakenMillis DESC LIMIT :limit")
    fun videosWithoutDuration(limit: Int): List<MediaItem>

    /** Newest first by date taken, after the keyset position ([beforeDate], [beforeId]) (PLAN 4.8). */
    @Query(
        "SELECT * FROM media WHERE mimeType LIKE :mimePrefix || '%' " +
            "AND (dateTakenMillis < :beforeDate OR (dateTakenMillis = :beforeDate AND id < :beforeId)) " +
            "ORDER BY dateTakenMillis DESC, id DESC LIMIT :limit",
    )
    fun newestPage(mimePrefix: String, beforeDate: Long, beforeId: String, limit: Int): List<MediaItem>

    @Query("SELECT * FROM folder")
    fun folders(): List<FolderEtag>

    @Upsert
    fun saveFolders(rows: List<FolderEtag>)

    @Query("DELETE FROM folder")
    fun clearFolders()

    @Query("SELECT COUNT(*) FROM media")
    fun mediaCount(): Int

    @Upsert
    fun upsertMedia(items: List<MediaItem>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    fun deleteMedia(ids: List<String>)

    @Upsert
    fun upsertDeleted(rows: List<DeletedMedia>)

    @Query("DELETE FROM deleted WHERE id IN (:ids)")
    fun forgetDeleted(ids: List<String>)

    /** Rows changed after [since], up to [top], after the keyset position ([afterGeneration], [afterId]). */
    @Query(
        "SELECT * FROM media WHERE generation > :since AND generation <= :top " +
            "AND (generation > :afterGeneration OR (generation = :afterGeneration AND id > :afterId)) " +
            "ORDER BY generation, id LIMIT :limit",
    )
    fun mediaPage(since: Long, top: Long, afterGeneration: Long, afterId: String, limit: Int): List<MediaItem>

    @Query(
        "SELECT * FROM deleted WHERE generation > :since AND generation <= :top " +
            "AND (generation > :afterGeneration OR (generation = :afterGeneration AND id > :afterId)) " +
            "ORDER BY generation, id LIMIT :limit",
    )
    fun deletedPage(since: Long, top: Long, afterGeneration: Long, afterId: String, limit: Int): List<DeletedMedia>

    @Query("SELECT MAX(generation) FROM deleted WHERE deletedAtMillis < :before")
    fun newestDeletionBefore(before: Long): Long?

    @Query("DELETE FROM deleted WHERE generation <= :generation")
    fun pruneDeletedThrough(generation: Long)

    @Query("DELETE FROM media")
    fun clearMedia()

    @Query("DELETE FROM deleted")
    fun clearDeleted()
}

@Database(
    entities = [MediaItem::class, DeletedMedia::class, SyncState::class, FolderEtag::class],
    version = 6,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "library.db")
                // Version 1 only ever existed on the development phone. Dropping it gives a new
                // instance ID and so a new collection ID, which makes MediaProvider rebuild.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .build()
    }
}
