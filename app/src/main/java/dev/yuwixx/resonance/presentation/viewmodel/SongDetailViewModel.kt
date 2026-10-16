// Backs SongDetailScreen: looks up a song by id, its lyrics, and its per-song listening stats
// (play count + total time listened) — independent of PlayerViewModel/the active playback
// session so the page works correctly even for a song that isn't currently playing.
package dev.yuwixx.resonance.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.database.dao.HistoryDao
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.repository.ArtworkRepository
import dev.yuwixx.resonance.data.repository.LyricsRepository
import dev.yuwixx.resonance.data.repository.LyricsResult
import dev.yuwixx.resonance.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SongDetailViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val activeLibrary: dev.yuwixx.resonance.data.repository.ActiveLibrary,
    private val lyricsRepository: LyricsRepository,
    private val historyDao: HistoryDao,
    private val artworkRepository: ArtworkRepository,
) : ViewModel() {

    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _lyricsResult = MutableStateFlow<LyricsResult>(LyricsResult.Loading)
    val lyricsResult: StateFlow<LyricsResult> = _lyricsResult.asStateFlow()

    private val _playCount = MutableStateFlow(0)
    val playCount: StateFlow<Int> = _playCount.asStateFlow()

    private val _totalListenedMs = MutableStateFlow(0L)
    val totalListenedMs: StateFlow<Long> = _totalListenedMs.asStateFlow()

    private var loadedSongId: Long? = null

    fun load(songId: Long) {
        if (loadedSongId == songId) return
        loadedSongId = songId
        viewModelScope.launch {
            val s = activeLibrary.songs.first().find { it.id == songId } ?: return@launch
            _song.value = s
            _playCount.value = historyDao.getPlayCountForSong(songId)
            _totalListenedMs.value = historyDao.getTotalListenTimeForSong(songId)
            _lyricsResult.value = LyricsResult.Loading
            _lyricsResult.value = lyricsRepository.getLyrics(
                songId = s.id,
                title = s.title,
                artist = s.displayArtist,
                album = s.album,
                durationMs = s.duration,
                songUri = s.uri,
                songPath = s.path,
                navidromeId = s.navidromeId,
            )
        }
    }

    suspend fun getSongArtworkUrl(song: Song): String? =
        artworkRepository.getSongArtworkUrl(song.albumId, song.title, song.displayArtist)
}
