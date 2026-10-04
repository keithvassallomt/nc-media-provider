package com.keithvassallo.ncmediaprovider.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** A row as SQLite holds it: integers as Long, text as String. */
typealias Row = Map<String, Any?>

/**
 * A library as a user's phone holds it, for the migration tests (#55). Each row has a value for
 * every column its table has had at any version, so it can be stored at any version: [insertRows]
 * keeps the columns that version has. A new table or column needs a value here.
 */
object LibraryRows {
    /** The picker's collection ID depends on it, so a migration must keep it and a rebuild must not. */
    const val INSTANCE_ID = "instance-from-before"

    val tables: Map<String, List<Row>> = linkedMapOf(
        "media" to listOf(
            mapOf(
                "id" to "101", "href" to "/remote.php/dav/files/alice/Photos/2024/beach.jpg", "etag" to "etag-101",
                "fileName" to "beach.jpg", "mimeType" to "image/jpeg", "sizeBytes" to 2_345_678L,
                "lastModifiedMillis" to 1_700_000_100_000L, "dateTakenMillis" to 1_700_000_000_000L, "durationMillis" to 0L,
                "width" to 4032L, "height" to 3024L, "isFavorite" to 1L, "generation" to 5L, "folder" to "/Photos/2024/",
                "mediaStoreUri" to "content://media/external/images/media/42",
                // Different from the values above, so a later migration that copied over them would show.
                "listedDateMillis" to 1_699_999_000_000L, "listedWidth" to 4000L, "listedHeight" to 3000L,
                "recordedMillis" to 0L, "isLiveVideo" to 0L,
            ),
            mapOf(
                "id" to "102", "href" to "/remote.php/dav/files/alice/Photos/2024/beach.mov", "etag" to "etag-102",
                "fileName" to "beach.mov", "mimeType" to "video/quicktime", "sizeBytes" to 3_456_789L,
                "lastModifiedMillis" to 1_700_000_100_000L, "dateTakenMillis" to 1_700_000_000_500L, "durationMillis" to 2_500L,
                "width" to 1920L, "height" to 1080L, "isFavorite" to 0L, "generation" to 4L, "folder" to "/Photos/2024/",
                "mediaStoreUri" to null,
                "listedDateMillis" to 1_699_999_000_500L, "listedWidth" to 1440L, "listedHeight" to 1080L,
                "recordedMillis" to 1_700_000_000_400L, "isLiveVideo" to 1L,
            ),
        ),
        "deleted" to listOf(mapOf("id" to "103", "generation" to 3L, "deletedAtMillis" to 1_700_000_300_000L)),
        "sync_state" to listOf(
            mapOf(
                "key" to 0L, "instanceId" to INSTANCE_ID, "generation" to 5L, "epoch" to 2L,
                "sourceKey" to "https://cloud.example.com|alice|/Photos/", "deletionFloor" to 1L,
                "lastFullListingMillis" to 1_700_000_400_000L, "imported" to 1L, "rootEtag" to "root-etag",
                "hiddenFolders" to "/Photos/Private/", "respectsNoMedia" to 1L, "duplicatePaths" to 1L,
                "lastCheckMillis" to 1_700_000_500_000L, "listingVersion" to 3L,
            ),
        ),
        "folder" to listOf(mapOf("path" to "/Photos/2024/", "etag" to "folder-etag")),
        "memories_file" to listOf(
            mapOf(
                "id" to "101", "etag" to "etag-101", "dayId" to 19_675L, "dateTakenMillis" to 1_700_000_050_000L,
                "width" to 4032L, "height" to 3024L, "liveId" to "live-101", "isVideo" to 0L,
            ),
        ),
        "memories_day" to listOf(mapOf("dayId" to 19_675L, "count" to 2L)),
        "album" to listOf(
            mapOf(
                "id" to "album-1", "href" to "/remote.php/dav/photos/alice/albums/Beach/", "name" to "Beach",
                "isShared" to 0L, "coverId" to "101", "signature" to "2|etag-101",
            ),
        ),
        "album_item" to listOf(
            mapOf(
                "albumId" to "album-1", "id" to "104", "href" to "/remote.php/dav/photos/alice/albums/Beach/shell.jpg",
                "etag" to "etag-104", "fileName" to "shell.jpg", "mimeType" to "image/jpeg", "sizeBytes" to 1_000L,
                "lastModifiedMillis" to 1_700_000_600_000L, "dateTakenMillis" to 1_700_000_600_000L,
                "width" to 800L, "height" to 600L, "isFavorite" to 0L,
            ),
        ),
        "person" to listOf(mapOf("id" to "person-1", "memoriesKey" to "alice/7", "name" to "Bob", "signature" to "3|etag-101")),
        "person_item" to listOf(mapOf("personId" to "person-1", "id" to "101")),
    )

    /** Stores [tables] in a database at any version, leaving out the tables and columns it doesn't have. */
    fun SupportSQLiteDatabase.insertRows() {
        val layout = layout()
        for ((table, rows) in tables) {
            val columns = layout[table] ?: continue
            for (row in rows) {
                val values = row.filterKeys(columns::contains)
                val names = values.keys.joinToString { "`$it`" }
                execSQL("INSERT INTO `$table` ($names) VALUES (${values.keys.joinToString { "?" }})", values.values.toTypedArray())
            }
        }
    }

    /** The library's tables and their columns, leaving out SQLite's and Room's own. */
    fun SupportSQLiteDatabase.layout(): Map<String, Set<String>> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").readAll { it.getString(0) }
            .filterNot { it in setOf("android_metadata", "room_master_table", "sqlite_sequence") }
            .associateWith { table -> query("PRAGMA table_info(`$table`)").readAll { it.getString(it.getColumnIndexOrThrow("name")) }.toSet() }

    /** Every row of [table], by column. */
    fun SupportSQLiteDatabase.rows(table: String): List<Row> =
        query("SELECT * FROM `$table`").readAll { cursor ->
            (0 until cursor.columnCount).associate { i ->
                cursor.getColumnName(i) to when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    else -> cursor.getString(i)
                }
            }
        }

    private fun <T> Cursor.readAll(read: (Cursor) -> T): List<T> = use { buildList { while (it.moveToNext()) add(read(it)) } }
}

/** The database version this build's Room code creates. */
fun currentVersion(context: Context): Int {
    val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
    try {
        return database.openHelper.readableDatabase.version
    } finally {
        database.close()
    }
}

/** Room refuses queries on the main thread, and under Robolectric the test runs on it. */
fun <T> offMainThread(block: () -> T): T {
    val executor = Executors.newSingleThreadExecutor()
    try {
        return executor.submit(Callable(block)).get()
    } finally {
        executor.shutdown()
    }
}
