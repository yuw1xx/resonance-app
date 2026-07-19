// ViewModel for all library data: songs, albums, artists, playlists, search, and MediaStore/Navidrome sync.
// Switches data sources transparently between LOCAL and NAVIDROME based on the active preference.
package dev.yuwixx.resonance.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.model.Album
import dev.yuwixx.resonance.data.model.Artist
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.database.entity.SongDownloadEntity
import dev.yuwixx.resonance.data.repository.ArtworkRepository
import dev.yuwixx.resonance.data.repository.MixRepository
import dev.yuwixx.resonance.data.repository.MusicRepository
import dev.yuwixx.resonance.data.repository.NavidromeDownloadRepository
import dev.yuwixx.resonance.data.repository.NavidromeRepository
import dev.yuwixx.resonance.data.repository.NavidromeSyncState
import dev.yuwixx.resonance.data.repository.PlaylistRepository
import dev.yuwixx.resonance.data.worker.MixGeneratorManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playlistRepository: PlaylistRepository,
    val prefs: ResonancePreferences,
    private val artworkRepository: ArtworkRepository,
    private val navidromeRepository: NavidromeRepository,
    private val navidromeDownloadRepository: NavidromeDownloadRepository,
    private val mixGeneratorManager: MixGeneratorManager,
    private val mixRepository: MixRepository,
) : ViewModel() {

    val downloadStates: StateFlow<Map<Long, SongDownloadEntity>> = navidromeDownloadRepository.downloadStates
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val downloadsStorageUsed: StateFlow<Long> = downloadStates
        .map { it.values.sumOf { d -> d.fileSizeBytes } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun downloadSong(songId: Long) {
        viewModelScope.launch { navidromeDownloadRepository.downloadSongs(listOf(songId)) }
    }

    fun downloadAlbum(albumId: Long) {
        viewModelScope.launch { navidromeDownloadRepository.downloadAlbum(albumId) }
    }

    fun downloadPlaylist(playlistId: Long) {
        viewModelScope.launch { navidromeDownloadRepository.downloadPlaylist(playlistId) }
    }

    fun removeDownload(songId: Long) {
        viewModelScope.launch { navidromeDownloadRepository.removeDownload(songId) }
    }

    fun removeAllDownloads() {
        viewModelScope.launch { navidromeDownloadRepository.removeAll() }
    }

    suspend fun getArtistArtworkUrl(artistName: String): String? =
        artworkRepository.getArtistArtworkUrl(artistName)

    suspend fun getSongArtworkUrl(song: Song): String? {
        if (!prefs.fetchAlbumArt.first()) return null
        return artworkRepository.getSongArtworkUrl(song.albumId, song.title, song.displayArtist)
    }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val navSyncState: StateFlow<NavidromeSyncState> = navidromeRepository.syncState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavidromeSyncState.Idle)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Debounce typing so search only runs after the user pauses for 300 ms.
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Song>> = combine(
        _searchQuery.debounce(300),
        prefs.musicSource,
    ) { query, source -> query to source }
        .flatMapLatest { (query, source) ->
            if (query.isBlank()) flowOf(emptyList())
            else when (source) {
                MusicSource.LOCAL     -> musicRepository.searchSongs(query)
                MusicSource.NAVIDROME -> allSongs.map { songs ->
                    val q = query.lowercase()
                    songs.filter {
                        it.title.lowercase().contains(q) ||
                        it.artist.lowercase().contains(q) ||
                        it.album.lowercase().contains(q)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateSearch(query: String) { _searchQuery.value = query }

    @OptIn(ExperimentalCoroutinesApi::class)
    val allSongs: StateFlow<List<Song>> = prefs.musicSource
        .flatMapLatest { source ->
            when (source) {
                MusicSource.LOCAL     -> musicRepository.allSongs
                MusicSource.NAVIDROME -> navidromeRepository.cachedSongs
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val allAlbums: StateFlow<List<Album>> = prefs.musicSource
        .flatMapLatest { source ->
            when (source) {
                MusicSource.LOCAL     -> musicRepository.allAlbums
                MusicSource.NAVIDROME -> navidromeRepository.cachedAlbums
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _navidromeArtists = MutableStateFlow<List<Artist>>(emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val allArtists: StateFlow<List<Artist>> = prefs.musicSource
        .flatMapLatest { source ->
            when (source) {
                MusicSource.LOCAL     -> musicRepository.allArtists
                MusicSource.NAVIDROME -> _navidromeArtists
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val allFolders: StateFlow<List<String>> = musicRepository.allFolders
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allGenres: StateFlow<List<String>> = musicRepository.allGenres
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allPlaylists: StateFlow<List<Playlist>> = combine(
        playlistRepository.allPlaylists,
        prefs.mixesLocation,
    ) { playlists, location ->
        if (location == "HOME") playlists.filter { !it.isMix } else playlists
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun getPlaylistById(id: Long): Flow<Playlist?> = playlistRepository.getPlaylistById(id)

    fun createPlaylist(name: String) {
        viewModelScope.launch { playlistRepository.createPlaylist(name) }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { playlistRepository.deletePlaylist(id) }
    }

    fun updatePlaylistArtwork(id: Long, artworkUri: Uri?) {
        viewModelScope.launch { playlistRepository.updatePlaylistArtwork(id, artworkUri) }
    }

    fun reorderPlaylist(playlistId: Long, songs: List<Song>) {
        viewModelScope.launch { playlistRepository.reorderPlaylist(playlistId, songs) }
    }

    suspend fun deduplicatePlaylist(playlistId: Long): Int =
        playlistRepository.deduplicatePlaylist(playlistId)

    fun renamePlaylist(id: Long, newName: String) {
        viewModelScope.launch { playlistRepository.renamePlaylist(id, newName) }
    }

    fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch { playlistRepository.addSongsToPlaylist(playlistId, songIds) }
    }

    fun removeSongsFromPlaylist(playlistId: Long, songIds: List<Long>) {
        viewModelScope.launch {
            songIds.forEach { songId ->
                playlistRepository.removeSongFromPlaylist(playlistId, songId)
            }
        }
    }

    fun exportPlaylistAsM3U(playlist: dev.yuwixx.resonance.data.model.Playlist): String =
        playlistRepository.exportPlaylistAsM3U(playlist)

    fun updateSongTags(
        songId: Long,
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        discNumber: Int,
    ) {
        viewModelScope.launch {
            musicRepository.updateSongTags(songId, title, artist, albumArtist, album, genre, year, trackNumber, discNumber)
            syncLibrary(force = true)
        }
    }

    fun syncLibrary(force: Boolean = false) {
        if (_isSyncing.value && !force) return
        viewModelScope.launch {
            when (prefs.musicSource.first()) {
                MusicSource.LOCAL -> {
                    _isSyncing.value = true
                    try { musicRepository.syncWithMediaStore() }
                    finally { _isSyncing.value = false }
                }
                MusicSource.NAVIDROME -> {
                    _isSyncing.value = true
                    try {
                        navidromeRepository.syncLibrary()
                        val artists = navidromeRepository.getAllArtists()
                        if (artists.isNotEmpty()) _navidromeArtists.value = artists
                    } finally {
                        _isSyncing.value = false
                    }
                }
            }
        }
    }

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory: StateFlow<Boolean> = _isClearingHistory.asStateFlow()

    fun clearHistory() {
        viewModelScope.launch {
            _isClearingHistory.value = true
            try {
                musicRepository.clearAllHistory()
                loadMostPlayed()
            } finally {
                _isClearingHistory.value = false
            }
        }
    }

    private val _mostPlayed = MutableStateFlow<List<Song>>(emptyList())
    val mostPlayed: StateFlow<List<Song>> = _mostPlayed.asStateFlow()

    fun loadMostPlayed() {
        viewModelScope.launch {
            _mostPlayed.value = musicRepository.getMostPlayedSongs(20)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val autoMixes: StateFlow<List<Playlist>> = prefs.musicSource
        .flatMapLatest { source ->
            prefs.mixesLocation.flatMapLatest { location ->
                if (location == "PLAYLISTS") {
                    flowOf(emptyList())
                } else {
                    playlistRepository.allPlaylists.map { playlists ->
                        playlists.filter { it.isMix && it.mixSource == source.name }
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun refreshMixes() {
        viewModelScope.launch {
            try { mixRepository.generateAndPersistMixes() }
            catch (_: Exception) {}
        }
    }

    init {
        viewModelScope.launch {
            val source = prefs.musicSource.first()
            if (source == MusicSource.NAVIDROME) {
                val hasCache = navidromeRepository.hasCachedData()
                if (hasCache) {
                    val artists = navidromeRepository.getAllArtists()
                    if (artists.isNotEmpty()) _navidromeArtists.value = artists
                    syncLibrary()
                } else {
                    syncLibrary()
                }
            } else {
                syncLibrary()
            }
        }
        loadMostPlayed()
        allSongs.onEach { loadMostPlayed() }.launchIn(viewModelScope)
        // Generate mixes on first load if none exist yet; runs inline so Room updates immediately.
        allSongs.filter { it.isNotEmpty() }
            .take(1)
            .onEach {
                val hasMixes = playlistRepository.allPlaylists.first().any { it.isMix }
                if (!hasMixes) {
                    viewModelScope.launch {
                        try { mixRepository.generateAndPersistMixes() }
                        catch (_: Exception) { mixGeneratorManager.triggerNow() }
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}
