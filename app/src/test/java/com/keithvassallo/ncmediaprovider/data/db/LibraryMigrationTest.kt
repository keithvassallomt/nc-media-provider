package com.keithvassallo.ncmediaprovider.data.db

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.keithvassallo.ncmediaprovider.data.LibraryStore
import com.keithvassallo.ncmediaprovider.data.db.LibraryRows.insertRows
import com.keithvassallo.ncmediaprovider.data.db.LibraryRows.layout
import com.keithvassallo.ncmediaprovider.data.db.LibraryRows.rows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Every exported schema version migrates to the current one with its rows intact (PLAN 9.6). Room's
 * MigrationTestHelper builds each old version from its exported schema, runs the app's own
 * auto-migrations and checks the result against the current schema. Version 1 has no path on
 * purpose; [LibraryRebuildTest] covers what happens to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val current: Int by lazy { currentVersion(context) }

    /** Versions with an exported schema, oldest first. */
    private val exported: List<Int> by lazy {
        context.assets.list(SCHEMAS).orEmpty().map { it.removeSuffix(".json").toInt() }.sorted()
    }

    /** Versions a user's phone can hold and the app migrates rather than rebuilds. */
    private val migrated: List<Int> by lazy { exported.filter { it in FIRST_MIGRATED until current } }

    @Test
    fun `every version up to the current one has an exported schema`() {
        assertEquals((1..current).toList(), exported)
    }

    @Test
    fun `every older version migrates to the current one with its rows intact`() {
        assertTrue(migrated.isNotEmpty())
        for (version in migrated) {
            val name = "v$version.db"
            val before = helper.createDatabase(name, version).use { database ->
                database.insertRows()
                database.layout()
            }
            val database = helper.runMigrationsAndValidate(name, current, true)
            val after = database.layout()
            assertEquals("tables after migrating from $version", LibraryRows.tables.keys, after.keys)
            for ((table, rows) in LibraryRows.tables) {
                val columns = before[table]
                val expected = if (columns == null) {
                    emptyList()
                } else {
                    rows.map { row -> after.getValue(table).associateWith { if (it in columns) row[it] else added(table, it, row) } }
                }
                assertEquals("$table after migrating from $version", expected.toSet(), database.rows(table).toSet())
            }
            database.close()
        }
    }

    @Test
    fun `rows stored before schema 7 take their listed values from the stored ones`() {
        helper.createDatabase("v6.db", 6).use { it.insertRows() }
        val database = helper.runMigrationsAndValidate("v6.db", current, true)
        val photo = database.rows("media").single { it["id"] == "101" }
        assertEquals(1_700_000_000_000L, photo["listedDateMillis"])
        assertEquals(4032L, photo["listedWidth"])
        assertEquals(3024L, photo["listedHeight"])
        // The app reads these from video headers and Memories at the next sync (PLAN 6.0 and 6.2).
        assertEquals(0L, photo["recordedMillis"])
        assertEquals(0L, photo["isLiveVideo"])
        database.close()
    }

    @Test
    fun `every older version opens through the app's own path and keeps its library`() {
        assertTrue(migrated.isNotEmpty())
        for (version in migrated) {
            context.deleteDatabase(APP_DATABASE)
            helper.createDatabase(APP_DATABASE, version).use { it.insertRows() }
            val database = LibraryDatabase.open(context)
            val state = offMainThread { LibraryStore(database).state() }
            // The same instance ID and generation keep the picker's collection ID and its sync.
            assertEquals("instance ID after opening $version", LibraryRows.INSTANCE_ID, state.instanceId)
            assertEquals("generation after opening $version", 5L, state.generation)
            assertEquals("files after opening $version", setOf("101", "102"), offMainThread { database.dao().allMedia() }.map { it.id }.toSet())
            database.close()
        }
        assertEquals(emptyList<ShadowLog.LogItem>(), ShadowLog.getLogsForTag("LibraryDatabase").filter { it.type >= Log.WARN })
    }

    /**
     * What [column], added to [table] after [row] was stored, holds once migrated. A new column
     * needs an entry: say what the rows a phone already holds should get.
     */
    private fun added(table: String, column: String, row: Row): Any? = when ("$table.$column") {
        "media.mediaStoreUri" -> null
        // ListedValuesMigration: until schema 7 the stored values were the listing's (PLAN 6.0 and 6.2).
        "media.listedDateMillis" -> row["dateTakenMillis"]
        "media.listedWidth" -> row["width"]
        "media.listedHeight" -> row["height"]
        "media.recordedMillis", "media.isLiveVideo" -> 0L
        "sync_state.hiddenFolders" -> ""
        "sync_state.respectsNoMedia", "sync_state.duplicatePaths", "sync_state.lastCheckMillis", "sync_state.listingVersion" -> 0L
        else -> throw AssertionError("$table.$column is new: add what rows stored before it should hold")
    }

    private companion object {
        val SCHEMAS: String = LibraryDatabase::class.java.canonicalName!!

        /** Version 1 only ever existed on the development phone and is rebuilt. */
        const val FIRST_MIGRATED = 2

        const val APP_DATABASE = "library.db"
    }
}
