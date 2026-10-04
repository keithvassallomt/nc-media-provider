package com.keithvassallo.ncmediaprovider.data.db

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.keithvassallo.ncmediaprovider.data.LibraryStore
import com.keithvassallo.ncmediaprovider.data.MediaItem
import com.keithvassallo.ncmediaprovider.data.db.LibraryRows.insertRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * A library the app can't bring up to this version is rebuilt through its real open path (PLAN 9.6):
 * an empty database that works, with a new instance ID so the picker gets a new collection ID, and
 * one warning line saying so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryRebuildTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val current: Int by lazy { currentVersion(context) }

    @Test
    fun `a version with no migration path is rebuilt as a new library`() {
        helper.createDatabase(APP_DATABASE, 1).use { it.insertRows() }
        assertRebuilt("No migration from library database version 1:")
    }

    @Test
    fun `a downgrade is rebuilt as a new library`() {
        // A newer build's database, left behind when an older build is installed over it.
        helper.createDatabase(APP_DATABASE, current).use {
            it.insertRows()
            it.version = current + 1
        }
        assertRebuilt("No migration from library database version ${current + 1}:")
    }

    @Test
    fun `a migration that throws is rebuilt as a new library`() {
        // The 6 to 7 migration adds columns to a table this file has lost.
        helper.createDatabase(APP_DATABASE, 6).use {
            it.insertRows()
            it.execSQL("DROP TABLE media")
        }
        assertRebuilt("Couldn't migrate the library database (SQLiteException")
    }

    @Test
    fun `a migration that leaves the wrong schema is rebuilt as a new library`() {
        // The 8 to 9 migration only adds the people tables, so Room's check finds the one missing.
        helper.createDatabase(APP_DATABASE, 8).use {
            it.insertRows()
            it.execSQL("DROP TABLE folder")
        }
        assertRebuilt("Couldn't migrate the library database (IllegalStateException")
    }

    @Test
    fun `a file that isn't a database is replaced by a new library`() {
        context.getDatabasePath(APP_DATABASE).apply { parentFile?.mkdirs() }.writeText("not a database")
        // SQLite's corruption handler replaces it before Room sees it, so nothing is logged here.
        assertRebuilt(warning = null)
    }

    /**
     * Opens the library as the app does and checks it starts a new one: empty, at generation 0 under
     * a new instance ID, able to take a listing, and still the same library at the next start.
     */
    private fun assertRebuilt(warning: String?) {
        val database = LibraryDatabase.open(context)
        val store = LibraryStore(database)
        val state = offMainThread { store.state() }
        assertNotEquals(LibraryRows.INSTANCE_ID, state.instanceId)
        assertEquals(0L, state.generation)
        assertEquals(0L, state.epoch)
        assertTrue(offMainThread { database.dao().allMedia() }.isEmpty())
        val listed = listOf(MediaItem("1", "/f/1.jpg", "e1", "1.jpg", "image/jpeg", 10, 1_000, 1_000))
        assertTrue(offMainThread { store.commit(listed, complete = true) })
        database.close()

        // Rebuilt once: the next start opens the new library as it is.
        val reopened = LibraryDatabase.open(context)
        val next = offMainThread { LibraryStore(reopened).state() }
        assertEquals(state.instanceId, next.instanceId)
        assertEquals(1L, next.generation)
        reopened.close()

        val warnings = ShadowLog.getLogsForTag("LibraryDatabase").filter { it.type == Log.WARN }.map { it.msg }
        if (warning == null) {
            assertEquals(emptyList<String>(), warnings)
        } else {
            assertEquals(1, warnings.size)
            assertTrue(warnings.single(), warnings.single().startsWith(warning))
        }
    }

    private companion object {
        const val APP_DATABASE = "library.db"
    }
}
