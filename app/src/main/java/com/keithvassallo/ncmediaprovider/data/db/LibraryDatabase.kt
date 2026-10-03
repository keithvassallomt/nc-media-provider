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
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.keithvassallo.ncmediaprovider.data.Album
import com.keithvassallo.ncmediaprovider.data.AlbumItem
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.data.MemoriesDay
import com.keithvassallo.ncmediaprovider.data.MemoriesFile
import com.keithvassallo.ncmediaprovider.data.MonthCount
import com.keithvassallo.ncmediaprovider.data.Person
import com.keithvassallo.ncmediaprovider.data.PersonItem
import com.keithvassallo.ncmediaprovider.data.PersonSummary
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

    /** Rows taken at or after [since], for the pre-cache's estimate (PLAN 5.6). */
    @Query("SELECT COUNT(*) FROM media WHERE dateTakenMillis >= :since AND isLiveVideo = 0")
    fun countTakenSince(since: Long): Int

    /** Photos and videos per month in local time, newest first, for the pre-cache's estimates (PLAN 5.6). */
    @Query(
        "SELECT strftime('%Y-%m', dateTakenMillis / 1000, 'unixepoch', 'localtime') AS month, COUNT(*) AS count " +
            "FROM media WHERE isLiveVideo = 0 GROUP BY month ORDER BY month DESC",
    )
    fun monthCounts(): List<MonthCount>

    /** Videos whose header hasn't been read yet (a 0 duration or recording time), newest first (PLAN 5.3 and 6.0). */
    @Query(
        "SELECT * FROM media WHERE mimeType LIKE 'video/%' AND (durationMillis = 0 OR recordedMillis = 0) " +
            "ORDER BY dateTakenMillis DESC LIMIT :limit",
    )
    fun videosWithoutHeader(limit: Int): List<MediaItem>

    /** Newest first by date taken, after the keyset position ([beforeDate], [beforeId]) (PLAN 4.8). */
    @Query(
        "SELECT * FROM media WHERE mimeType LIKE :mimePrefix || '%' AND isLiveVideo = 0 " +
            "AND (dateTakenMillis < :beforeDate OR (dateTakenMillis = :beforeDate AND id < :beforeId)) " +
            "ORDER BY dateTakenMillis DESC, id DESC LIMIT :limit",
    )
    fun newestPage(mimePrefix: String, beforeDate: Long, beforeId: String, limit: Int): List<MediaItem>

    /** The photo keyboard's grid (PLAN 4.8): older than the keyset position, newest first; favourites only when asked. */
    @Query(
        "SELECT * FROM media WHERE mimeType LIKE :mimePrefix || '%' AND isLiveVideo = 0 AND (:favouritesOnly = 0 OR isFavorite = 1) " +
            "AND (dateTakenMillis < :date OR (dateTakenMillis = :date AND id < :id)) " +
            "ORDER BY dateTakenMillis DESC, id DESC LIMIT :limit",
    )
    fun keyboardOlder(mimePrefix: String, favouritesOnly: Boolean, date: Long, id: String, limit: Int): List<MediaItem>

    /** The next newer page after a jump to a year, oldest first. */
    @Query(
        "SELECT * FROM media WHERE mimeType LIKE :mimePrefix || '%' AND isLiveVideo = 0 AND (:favouritesOnly = 0 OR isFavorite = 1) " +
            "AND (dateTakenMillis > :date OR (dateTakenMillis = :date AND id > :id)) " +
            "ORDER BY dateTakenMillis ASC, id ASC LIMIT :limit",
    )
    fun keyboardNewer(mimePrefix: String, favouritesOnly: Boolean, date: Long, id: String, limit: Int): List<MediaItem>

    @Query(
        "SELECT DISTINCT CAST(strftime('%Y', dateTakenMillis / 1000, 'unixepoch', 'localtime') AS INTEGER) AS year FROM media " +
            "WHERE mimeType LIKE :mimePrefix || '%' AND isLiveVideo = 0 AND (:favouritesOnly = 0 OR isFavorite = 1) ORDER BY year DESC",
    )
    fun keyboardYears(mimePrefix: String, favouritesOnly: Boolean): List<Int>

    @Query("SELECT * FROM folder")
    fun folders(): List<FolderEtag>

    @Upsert
    fun saveFolders(rows: List<FolderEtag>)

    @Query("DELETE FROM folder")
    fun clearFolders()

    /** Rows the picker shows: live-photo videos are reported to it as deleted (PLAN 6.2). */
    @Query("SELECT COUNT(*) FROM media WHERE isLiveVideo = 0")
    fun mediaCount(): Int

    @Query("SELECT COUNT(*) FROM media WHERE isLiveVideo = 1")
    fun liveVideoCount(): Int

    /** Rows whose values come from Memories: it indexed the file as it is now (PLAN 6.2). */
    @Query("SELECT COUNT(*) FROM media f JOIN memories_file m ON m.id = f.id AND m.etag = f.etag")
    fun memoriesMatchedCount(): Int

    @Query("SELECT * FROM memories_file")
    fun memoriesFiles(): List<MemoriesFile>

    @Query("SELECT * FROM memories_file WHERE id IN (:ids)")
    fun memoriesWithIds(ids: List<String>): List<MemoriesFile>

    @Query("SELECT COUNT(*) FROM memories_file")
    fun memoriesFileCount(): Int

    /** Days holding a file whose etag moved on since Memories was read: an edit, say. */
    @Query("SELECT DISTINCT m.dayId FROM memories_file m JOIN media f ON f.id = m.id WHERE f.etag != m.etag")
    fun staleMemoriesDays(): List<Int>

    @Upsert
    fun saveMemoriesFiles(rows: List<MemoriesFile>)

    @Query("DELETE FROM memories_file WHERE dayId IN (:days)")
    fun deleteMemoriesDays(days: List<Int>)

    @Query("SELECT DISTINCT dayId FROM memories_file")
    fun memoriesFileDays(): List<Int>

    @Query("DELETE FROM memories_file")
    fun clearMemoriesFiles()

    @Query("SELECT * FROM album ORDER BY name")
    fun albums(): List<Album>

    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Album?

    @Upsert
    fun saveAlbums(rows: List<Album>)

    @Query("DELETE FROM album WHERE id IN (:ids)")
    fun deleteAlbums(ids: List<String>)

    @Query("SELECT * FROM album_item WHERE albumId = :albumId")
    fun albumItems(albumId: String): List<AlbumItem>

    @Query("SELECT * FROM album_item WHERE id = :id LIMIT 1")
    fun albumItem(id: String): AlbumItem?

    @Query("SELECT * FROM album_item")
    fun allAlbumItems(): List<AlbumItem>

    @Query("SELECT * FROM media WHERE href LIKE :pattern")
    fun mediaWithHrefLike(pattern: String): List<MediaItem>

    @Upsert
    fun saveAlbumItems(rows: List<AlbumItem>)

    @Query("DELETE FROM album_item WHERE albumId IN (:albumIds)")
    fun deleteAlbumItems(albumIds: List<String>)

    @Query("DELETE FROM album")
    fun clearAlbums()

    @Query("DELETE FROM album_item")
    fun clearAlbumItems()

    @Query("SELECT * FROM person")
    fun persons(): List<Person>

    @Query("SELECT * FROM person WHERE id = :id")
    fun person(id: String): Person?

    @Upsert
    fun savePersons(rows: List<Person>)

    @Query("DELETE FROM person WHERE id IN (:ids)")
    fun deletePersons(ids: List<String>)

    @Upsert
    fun savePersonItems(rows: List<PersonItem>)

    @Query("DELETE FROM person_item WHERE personId IN (:personIds)")
    fun deletePersonItems(personIds: List<String>)

    @Query("DELETE FROM person")
    fun clearPersons()

    @Query("DELETE FROM person_item")
    fun clearPersonItems()

    /** Each person with how many of their photos the picker shows, and the newest one's date. */
    @Query(
        "SELECT p.id AS id, p.name AS name, COUNT(m.id) AS shown, COALESCE(MAX(m.dateTakenMillis), 0) AS newest " +
            "FROM person p JOIN person_item i ON i.personId = p.id JOIN media m ON m.id = i.id AND m.isLiveVideo = 0 GROUP BY p.id",
    )
    fun personSummaries(): List<PersonSummary>

    /** A page of one person's photos in the library, in file ID order after [afterId]. */
    @Query(
        "SELECT m.* FROM media m JOIN person_item i ON i.id = m.id " +
            "WHERE i.personId = :personId AND m.isLiveVideo = 0 AND m.id > :afterId ORDER BY m.id LIMIT :limit",
    )
    fun personMedia(personId: String, afterId: String, limit: Int): List<MediaItem>

    /** One person's photos for the keyboard, older than the keyset position, newest first. */
    @Query(
        "SELECT m.* FROM media m JOIN person_item i ON i.id = m.id " +
            "WHERE i.personId = :personId AND m.mimeType LIKE :mimePrefix || '%' AND m.isLiveVideo = 0 " +
            "AND (m.dateTakenMillis < :date OR (m.dateTakenMillis = :date AND m.id < :id)) " +
            "ORDER BY m.dateTakenMillis DESC, m.id DESC LIMIT :limit",
    )
    fun personOlder(personId: String, mimePrefix: String, date: Long, id: String, limit: Int): List<MediaItem>

    @Query(
        "SELECT m.* FROM media m JOIN person_item i ON i.id = m.id " +
            "WHERE i.personId = :personId AND m.mimeType LIKE :mimePrefix || '%' AND m.isLiveVideo = 0 " +
            "AND (m.dateTakenMillis > :date OR (m.dateTakenMillis = :date AND m.id > :id)) " +
            "ORDER BY m.dateTakenMillis ASC, m.id ASC LIMIT :limit",
    )
    fun personNewer(personId: String, mimePrefix: String, date: Long, id: String, limit: Int): List<MediaItem>

    @Query(
        "SELECT DISTINCT CAST(strftime('%Y', m.dateTakenMillis / 1000, 'unixepoch', 'localtime') AS INTEGER) AS year " +
            "FROM media m JOIN person_item i ON i.id = m.id " +
            "WHERE i.personId = :personId AND m.mimeType LIKE :mimePrefix || '%' AND m.isLiveVideo = 0 ORDER BY year DESC",
    )
    fun personYears(personId: String, mimePrefix: String): List<Int>

    @Query("SELECT * FROM memories_day")
    fun memoriesDays(): List<MemoriesDay>

    @Upsert
    fun saveMemoriesDayCounts(rows: List<MemoriesDay>)

    @Query("DELETE FROM memories_day")
    fun clearMemoriesDayCounts()

    @Query("DELETE FROM memories_day WHERE dayId IN (:days)")
    fun deleteMemoriesDayCounts(days: List<Int>)

    @Upsert
    fun upsertMedia(items: List<MediaItem>)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    fun deleteMedia(ids: List<String>)

    @Upsert
    fun upsertDeleted(rows: List<DeletedMedia>)

    @Query("DELETE FROM deleted WHERE id IN (:ids)")
    fun forgetDeleted(ids: List<String>)

    /**
     * Rows changed after [since], up to [top], after the keyset position ([afterGeneration], [afterId]).
     * Live-photo videos are left out: the deletion journal reports them (PLAN 6.2).
     */
    @Query(
        "SELECT * FROM media WHERE generation > :since AND generation <= :top AND isLiveVideo = 0 " +
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

/**
 * Rows stored before schema 7 hold the listing's values only, so those become the listed values
 * too; the next sync's header reads and Memories pass add the rest (PLAN 6.0 and 6.2).
 */
class ListedValuesMigration : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        connection.execSQL("UPDATE media SET listedDateMillis = dateTakenMillis, listedWidth = width, listedHeight = height")
    }
}

@Database(
    entities = [
        MediaItem::class, DeletedMedia::class, SyncState::class, FolderEtag::class, MemoriesFile::class, MemoriesDay::class,
        Album::class, AlbumItem::class, Person::class, PersonItem::class,
    ],
    version = 9,
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = ListedValuesMigration::class),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
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
