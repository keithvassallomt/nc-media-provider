package com.keithvassallo.ncmediaprovider.data.db

import android.content.Context
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

/** A file removed from the library, and the generation that removed it (the deletion journal). */
@Entity(tableName = "deleted", indices = [Index(value = ["generation", "id"])])
data class DeletedMedia(
    @PrimaryKey val id: String,
    val generation: Long,
    val deletedAtMillis: Long,
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

@Database(entities = [MediaItem::class, DeletedMedia::class, SyncState::class], version = 1)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "library.db").build()
    }
}
