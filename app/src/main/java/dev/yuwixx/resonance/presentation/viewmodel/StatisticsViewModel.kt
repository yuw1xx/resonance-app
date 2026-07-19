package dev.yuwixx.resonance.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.database.dao.HistoryDao
import dev.yuwixx.resonance.data.database.dao.HourPlayCount
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.SongEntity
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
    val topSongs: List<Pair<SongEntity, Int>>,
    val topArtists: List<Pair<String, Int>>,
    val hourlyActivity: List<HourPlayCount>,
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val historyDao: HistoryDao,
    private val songDao: SongDao,
) : ViewModel() {

    private val _period = MutableStateFlow(StatsPeriod.WEEK)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _stats = MutableStateFlow<StatisticsData?>(null)
    val stats: StateFlow<StatisticsData?> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            _period.collectLatest { period ->
                // Re-compute stats whenever the history table changes (new plays, pruning, etc.).
                // debounce prevents cascading reloads from rapid inserts.
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

        // Top songs — batch fetch, no N+1
        val topIds  = historyDao.getTopSongIds(from, to, 10)
        val songMap = songDao.getSongsByIds(topIds.map { it.songId }).associateBy { it.id }
        val topSongs = topIds.mapNotNull { entry -> songMap[entry.songId]?.let { it to entry.playCount } }

        // Top artists — batch fetch, no N+1
        val allTopIds         = historyDao.getTopSongIds(from, to, 100)
        val allSongsForArtists = songDao.getSongsByIds(allTopIds.map { it.songId }).associateBy { it.id }
        val playCounts        = allTopIds.associate { it.songId to it.playCount }
        val artistCounts      = mutableMapOf<String, Int>()
        allSongsForArtists.forEach { (id, song) ->
            val artist = song.artist.ifEmpty { "Unknown" }
            artistCounts[artist] = (artistCounts[artist] ?: 0) + (playCounts[id] ?: 0)
        }
        val topArtists = artistCounts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key to it.value }

        val listenStreak = computeStreak(historyDao.getDistinctListenDates())

        return StatisticsData(
            totalListenTimeMs = totalTime,
            playCount         = playCount,
            uniqueTrackCount  = uniqueTrackCount,
            listenStreak      = listenStreak,
            topSongs          = topSongs,
            topArtists        = topArtists,
            hourlyActivity    = hourly,
        )
    }

    private fun computeStreak(dates: List<String>): Int {
        if (dates.isEmpty()) return 0
        // dates[] comes from history's date(listenedAt / 1000, 'unixepoch', 'localtime') — i.e.
        // device-local calendar dates — so today/yesterday must be computed in the same (default)
        // timezone. Forcing UTC here used to desync from that for roughly half of every day at
        // any negative UTC offset, intermittently zeroing the streak.
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val today     = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = sdf.format(cal.time)

        // Streak is only active if the most recent listen was today or yesterday.
        if (dates[0] != today && dates[0] != yesterday) return 0

        var streak = 1
        for (i in 1 until dates.size) {
            val d1 = sdf.parse(dates[i - 1]) ?: break
            val d2 = sdf.parse(dates[i])     ?: break
            val diffDays = ((d1.time - d2.time) / (24L * 60 * 60 * 1000)).toInt()
            if (diffDays == 1) streak++ else break
        }
        return streak
    }

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
