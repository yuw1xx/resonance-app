package dev.yuwixx.resonance.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.yuwixx.resonance.data.database.entity.HistoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

// HistoryDao's date/hour queries use SQLite's date()/strftime(..., 'localtime'), which resolves
// against the JVM's default timezone — pinned to UTC here so expected timestamps are
// deterministic regardless of the machine/CI runner this runs on.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryDaoTest {
    private lateinit var db: ResonanceDatabase
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ResonanceDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    // August 2026: the 8th is a Saturday, 9th a Sunday, 10th a Monday.
    private fun utcMillis(hour: Int, minute: Int = 0, day: Int = 10): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.AUGUST, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun history(songId: Long, hour: Int, minute: Int = 0, day: Int = 10, durationMs: Long = 1000) =
        HistoryEntity(songId = songId, listenedAt = utcMillis(hour, minute, day), durationListened = durationMs, percentageListened = 1f)

    @Test
    fun `total listen time and play count sum within range`() = runTest {
        val dao = db.historyDao()
        dao.insertHistory(history(songId = 1, hour = 9, durationMs = 60_000))
        dao.insertHistory(history(songId = 2, hour = 10, durationMs = 90_000))

        val from = utcMillis(0)
        val to = utcMillis(23, 59)
        assertEquals(150_000L, dao.getTotalListenTime(from, to))
        assertEquals(2, dao.getPlayCount(from, to))
        assertEquals(2, dao.getUniqueTrackCount(from, to))
    }

    @Test
    fun `top song ids are ordered by play count descending`() = runTest {
        val dao = db.historyDao()
        repeat(3) { dao.insertHistory(history(songId = 1, hour = 9, minute = it)) }
        dao.insertHistory(history(songId = 2, hour = 10))

        val top = dao.getTopSongIds(utcMillis(0), utcMillis(23, 59), limit = 5)
        assertEquals(listOf(1L, 2L), top.map { it.songId })
        assertEquals(3, top[0].playCount)
        assertEquals(1, top[1].playCount)
    }

    @Test
    fun `plays by hour buckets correctly`() = runTest {
        val dao = db.historyDao()
        dao.insertHistory(history(songId = 1, hour = 9))
        dao.insertHistory(history(songId = 2, hour = 9, minute = 30))
        dao.insertHistory(history(songId = 3, hour = 14))

        val hourly = dao.getPlaysByHour(utcMillis(0), utcMillis(23, 59)).associate { it.hour to it.playCount }
        assertEquals(2, hourly[9])
        assertEquals(1, hourly[14])
        assertNull(hourly[10])
    }

    @Test
    fun `distinct listen dates are ordered most recent first`() = runTest {
        val dao = db.historyDao()
        dao.insertHistory(history(songId = 1, hour = 9, day = 8))
        dao.insertHistory(history(songId = 1, hour = 9, day = 10))
        dao.insertHistory(history(songId = 1, hour = 9, day = 9))

        assertEquals(listOf("2026-08-10", "2026-08-09", "2026-08-08"), dao.getDistinctListenDates())
    }

    @Test
    fun `daily totals group and sum per day`() = runTest {
        val dao = db.historyDao()
        dao.insertHistory(history(songId = 1, hour = 9, day = 9, durationMs = 30_000))
        dao.insertHistory(history(songId = 1, hour = 20, day = 9, durationMs = 20_000))
        dao.insertHistory(history(songId = 1, hour = 9, day = 10, durationMs = 15_000))

        val totals = dao.getDailyTotals(utcMillis(0, day = 1), utcMillis(23, 59, day = 20))
        assertEquals(2, totals.size)
        val day9 = totals.first { it.day == "2026-08-09" }
        assertEquals(50_000L, day9.totalMs)
        assertEquals(2, day9.playCount)
    }

    @Test
    fun `plays by day of week use sqlite's 0-indexed sunday convention`() = runTest {
        val dao = db.historyDao()
        // 2026-08-10 is a Monday -> strftime('%w') = 1.
        dao.insertHistory(history(songId = 1, hour = 9, day = 10))

        val dow = dao.getPlaysByDayOfWeek(utcMillis(0, day = 1), utcMillis(23, 59, day = 20))
        assertEquals(1, dow.size)
        assertEquals(1, dow[0].dayOfWeek)
        assertEquals(1, dow[0].playCount)
    }

    @Test
    fun `per-song play count and total time only count that song`() = runTest {
        val dao = db.historyDao()
        dao.insertHistory(history(songId = 1, hour = 9, durationMs = 40_000))
        dao.insertHistory(history(songId = 1, hour = 10, durationMs = 20_000))
        dao.insertHistory(history(songId = 2, hour = 11, durationMs = 99_000))

        assertEquals(2, dao.getPlayCountForSong(1))
        assertEquals(60_000L, dao.getTotalListenTimeForSong(1))
        assertEquals(1, dao.getPlayCountForSong(2))
    }
}
