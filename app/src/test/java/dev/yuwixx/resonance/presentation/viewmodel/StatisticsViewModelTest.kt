package dev.yuwixx.resonance.presentation.viewmodel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.yuwixx.resonance.data.database.ResonanceDatabase
import dev.yuwixx.resonance.data.database.entity.HistoryEntity
import dev.yuwixx.resonance.data.database.entity.SongEntity
import dev.yuwixx.resonance.data.repository.ArtworkRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// A real in-memory database, since the statistics are computed in SQL. ArtworkRepository isn't
// used by computeStats and is mocked.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsViewModelTest {
    private lateinit var db: ResonanceDatabase
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Run Room queries on the calling thread so the debounced flow follows the test clock.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ResonanceDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun song(id: Long, title: String, artist: String) = SongEntity(
        id = id, uri = "content://media/$id", title = title, artist = artist,
        albumArtist = artist, album = "Album", albumId = 1, genre = "",
        duration = 200_000, size = 1000, bitrate = 128, sampleRate = 44100,
        trackNumber = 0, discNumber = 0, year = 2020, dateAdded = 0, dateModified = 0,
        path = "/music/$id.mp3", folder = "/music", mimeType = "audio/mpeg",
        replayGainTrack = null, replayGainAlbum = null,
    )

    @Test
    fun `computeStats aggregates top songs and artists from history`() = runTest(dispatcher) {
        db.songDao().upsertSongs(listOf(song(1, "Song A", "Artist X"), song(2, "Song B", "Artist Y")))
        val now = System.currentTimeMillis()
        repeat(3) {
            db.historyDao().insertHistory(
                HistoryEntity(songId = 1, listenedAt = now, durationListened = 60_000, percentageListened = 1f)
            )
        }
        db.historyDao().insertHistory(
            HistoryEntity(songId = 2, listenedAt = now, durationListened = 30_000, percentageListened = 1f)
        )

        val viewModel = StatisticsViewModel(db.historyDao(), db.songDao(), mockk<ArtworkRepository>(relaxed = true))
        viewModel.setPeriod(StatsPeriod.ALL_TIME)
        advanceUntilIdle()

        val stats = requireNotNull(viewModel.stats.value)
        assertEquals(4, stats.playCount)
        assertEquals(210_000L, stats.totalListenTimeMs) // 3x60_000 (song 1) + 30_000 (song 2)
        assertEquals(listOf(1L, 2L), stats.topSongs.map { it.first.id })
        assertEquals(3, stats.topSongs.first { it.first.id == 1L }.second)
        assertEquals(listOf("Artist X", "Artist Y"), stats.topArtists.map { it.first })
    }

    @Test
    fun `empty history produces zeroed stats, not an error`() = runTest(dispatcher) {
        val viewModel = StatisticsViewModel(db.historyDao(), db.songDao(), mockk<ArtworkRepository>(relaxed = true))
        viewModel.setPeriod(StatsPeriod.ALL_TIME)
        advanceUntilIdle()

        val stats = requireNotNull(viewModel.stats.value)
        assertEquals(0, stats.playCount)
        assertEquals(0L, stats.totalListenTimeMs)
        assertEquals(0, stats.longestStreak)
        assertEquals(emptyList<Any>(), stats.topSongs)
    }
}
