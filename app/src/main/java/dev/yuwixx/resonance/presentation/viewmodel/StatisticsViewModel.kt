package dev.yuwixx.resonance.presentation.viewmodel

import kotlinx.coroutines.FlowPreview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.database.dao.DailyTotal
import dev.yuwixx.resonance.data.database.dao.DayOfWeekPlayCount
import dev.yuwixx.resonance.data.database.dao.HistoryDao
import dev.yuwixx.resonance.data.database.dao.HourPlayCount
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.SongEntity
import dev.yuwixx.resonance.data.repository.ArtworkRepository
import dev.yuwixx.resonance.domain.usecase.StreakCalculator
import dev.yuwixx.resonance.presentation.components.audioFormatLabel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

enum class StatsPeriod(val label: String) {
    TODAY("Today"),
    WEEK("This Week"),
    MONTH("This Month"),
    ALL_TIME("All Time"),
}

data class StatisticsData(
    val totalListenTimeMs: Long,
    val playCount: Int,
    val uniqueTrackCount: Int,
    val listenStreak: Int,
    val longestStreak: Int,
    val avgSessionMs: Long,
    val topSongs: List<Pair<SongEntity, Int>>,
    val topArtists: List<Pair<String, Int>>,
    val topAlbums: List<Pair<SongEntity, Int>>,
    val hourlyActivity: List<HourPlayCount>,
    val dailyTrend: List<DailyTotal>,
    val genreBreakdown: List<Pair<String, Int>>,
    val dayOfWeekActivity: List<DayOfWeekPlayCount>,
    val formatBreakdown: List<Pair<String, Int>>,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val historyDao: HistoryDao,
    private val songDao: SongDao,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.WEEK)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _stats = MutableStateFlow<StatisticsData?>(null)
    val stats: StateFlow<StatisticsData?> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            _period.collectLatest { period ->
                // Recompute when history changes; the debounce absorbs bursts of inserts.
                historyDao.getRecentHistory()
                    .debounce(300L)
                    .collect { _ -> _stats.value = computeStats(period) }
            }
        }
    }

    fun setPeriod(p: StatsPeriod) { _period.value = p }

    private suspend fun computeStats(period: StatsPeriod): StatisticsData {
        val (from, to) = periodRange(period)

        val totalTime        = historyDao.getTotalListenTime(from, to)
        val playCount        = historyDao.getPlayCount(from, to)
        val uniqueTrackCount = historyDao.getUniqueTrackCount(from, to)
        val hourly           = historyDao.getPlaysByHour(from, to)
        val dailyTrend        = historyDao.getDailyTotals(from, to)
        val dayOfWeekActivity = historyDao.getPlaysByDayOfWeek(from, to)

        // Top songs — batch fetch, no N+1
        val topIds  = historyDao.getTopSongIds(from, to, 10)
        val songMap = songDao.getSongsByIds(topIds.map { it.songId }).associateBy { it.id }
        val topSongs = topIds.mapNotNull { entry -> songMap[entry.songId]?.let { it to entry.playCount } }

        // Top artists / albums / genre / format breakdown — all sampled from the same top-100
        // batch fetch (no N+1), same approach the original Top Artists computation already used.
        val allTopIds         = historyDao.getTopSongIds(from, to, 100)
        val allSongsForArtists = songDao.getSongsByIds(allTopIds.map { it.songId }).associateBy { it.id }
        val playCounts        = allTopIds.associate { it.songId to it.playCount }

        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts  = mutableMapOf<Long, Int>()
        val albumSongs   = mutableMapOf<Long, SongEntity>()
        val genreCounts  = mutableMapOf<String, Int>()
        val formatCounts = mutableMapOf<String, Int>()
        allSongsForArtists.forEach { (id, song) ->
            val count = playCounts[id] ?: 0
            val artist = song.artist.ifEmpty { "Unknown" }
            artistCounts[artist] = (artistCounts[artist] ?: 0) + count

            albumCounts[song.albumId] = (albumCounts[song.albumId] ?: 0) + count
            albumSongs.putIfAbsent(song.albumId, song)

            val genre = song.genre.ifEmpty { "Unknown" }
            genreCounts[genre] = (genreCounts[genre] ?: 0) + count

            val format = audioFormatLabel(song.mimeType)
            formatCounts[format] = (formatCounts[format] ?: 0) + count
        }
        val topArtists = artistCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }
        val topAlbums = albumCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .mapNotNull { entry -> albumSongs[entry.key]?.let { it to entry.value } }
        val genreBreakdown = genreCounts.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key to it.value }
        val formatBreakdown = formatCounts.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

        val allDates = historyDao.getDistinctListenDates()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val today = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(cal.time)
        val listenStreak = StreakCalculator.computeStreak(allDates, today, yesterday)
        val longestStreak = StreakCalculator.computeLongestStreak(allDates)
        val avgSessionMs = if (playCount > 0) totalTime / playCount else 0L

        return StatisticsData(
            totalListenTimeMs = totalTime,
            playCount         = playCount,
            uniqueTrackCount  = uniqueTrackCount,
            listenStreak      = listenStreak,
            longestStreak     = longestStreak,
            avgSessionMs      = avgSessionMs,
            topSongs          = topSongs,
            topArtists        = topArtists,
            topAlbums         = topAlbums,
            hourlyActivity    = hourly,
            dailyTrend        = dailyTrend,
            genreBreakdown    = genreBreakdown,
            dayOfWeekActivity = dayOfWeekActivity,
            formatBreakdown   = formatBreakdown,
        )
    }

    suspend fun getSongArtworkUrl(song: SongEntity): String? =
        artworkRepository.getSongArtworkUrl(song.albumId, song.title, song.artist)

    suspend fun getArtistArtworkUrl(artistName: String): String? =
        artworkRepository.getArtistArtworkUrl(artistName)


    private fun periodRange(period: StatsPeriod): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        return when (period) {
            StatsPeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsPeriod.WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsPeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0);      cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            StatsPeriod.ALL_TIME -> 0L to now
        }
    }
}
