package dev.yuwixx.resonance.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ResonanceDatabase::class.java,
    )

    @Test
    fun `9 to 10 keeps data, adds queue position and drops orphaned playlist refs`() = runTest {
        helper.createDatabase(dbName, 9).apply {
            execSQL("INSERT INTO playlists (id, name, isReadOnly, artworkUri, dateCreated, dateModified, isMix, lastMixGenerated) VALUES (1, 'Kept', 0, NULL, 0, 0, 0, 0)")
            execSQL("INSERT INTO playlist_songs (playlistId, songId, position) VALUES (1, 10, 0)")
            execSQL("INSERT INTO playlist_songs (playlistId, songId, position) VALUES (99, 11, 0)")
            execSQL("INSERT INTO queues (id, songIds, currentIndex, shuffleEnabled, repeatMode, originalOrder, savedAt) VALUES (0, '10,11', 1, 0, 'NONE', '', 0)")
            close()
        }

        // Validates the migrated schema (columns, indices, new tables) against the v10 export.
        val db = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        db.query("SELECT playlistId FROM playlist_songs").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1L, c.getLong(0))
        }
        db.query("SELECT positionMs, currentIndex FROM queues").use { c ->
            c.moveToFirst()
            assertEquals(0L, c.getLong(0))
            assertEquals(1, c.getInt(1))
        }
        db.close()
    }

    @Test
    fun `full migration chain from 3 opens with the current schema`() {
        helper.createDatabase(dbName, 3).close()
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), ResonanceDatabase::class.java, dbName)
            .addMigrations(
                MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            )
            .allowMainThreadQueries()
            .build()
            .apply {
                openHelper.writableDatabase // forces migration + validation
                close()
            }
    }
}
