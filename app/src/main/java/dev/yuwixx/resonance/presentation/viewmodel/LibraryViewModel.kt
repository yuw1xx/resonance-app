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
    private val navidromeSyncRepository: dev.yuwixx.resonance.data.repository.NavidromeSyncRepository,
    private val playlistFolderSync: dev.yuwixx.resonance.data.playlist.PlaylistFolderSync,
    private val navidromeDownloadRepository: NavidromeDownloadRepository,
    private val mixGeneratorManager: MixGeneratorManager,
    private val mixRepository: MixRepository,
    private val m3uImporter: dev.yuwixx.resonance.data.playlist.M3uImporter,
) : ViewModel() {

    // ─── M3U import / export ───

    sealed interface M3uImportState {
        data object Idle : M3uImportState
        /** Some picked files clash with existing playlist names; ask how to handle them. */
        data class ConfirmConflicts(val previews: List<dev.yuwixx.resonance.data.playlist.M3uImporter.Preview>) : M3uImportState
        data class Importing(val done: Int, val total: Int) : M3uImportState
        data class Finished(
            val results: List<dev.yuwixx.resonance.data.playlist.M3uImporter.Result>,
            val failures: List<String>,
        ) : M3uImportState
    }

    private val _m3uImportState = MutableStateFlow<M3uImportState>(M3uImportState.Idle)
    val m3uImportState: StateFlow<M3uImportState> = _m3uImportState.asStateFlow()

    fun startM3uImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _m3uImportState.value = M3uImportState.Importing(0, uris.size)
            val previews = m3uImporter.preview(uris)
            if (previews.any { it.existingPlaylistId != null }) {
                _m3uImportState.value = M3uImportState.ConfirmConflicts(previews)
            } else {
                runM3uImport(uris, dev.yuwixx.resonance.data.playlist.M3uImporter.ConflictMode.KEEP_BOTH)
            }
        }
    }

    fun confirmM3uImport(mode: dev.yuwixx.resonance.data.playlist.M3uImporter.ConflictMode) {
        val state = _m3uImportState.value as? M3uImportState.ConfirmConflicts ?: return
        viewModelScope.launch { runM3uImport(state.previews.map { it.uri }, mode) }
    }

    fun dismissM3uImport() {
        _m3uImportState.value = M3uImportState.Idle
    }

    private suspend fun runM3uImport(uris: List<Uri>, mode: dev.yuwixx.resonance.data.playlist.M3uImporter.ConflictMode) {
        val results = mutableListOf<dev.yuwixx.resonance.data.playlist.M3uImporter.Result>()
        val failures = mutableListOf<String>()
        uris.forEachIndexed { i, uri ->
            _m3uImportState.value = M3uImportState.Importing(i, uris.size)
            try {
                results += m3uImporter.import(uri, mode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += e.message ?: m3uImporter.displayNameStem(uri)
            }
        }
        _m3uImportState.value = M3uImportState.Finished(results, failures)
    }

    /** Writes the playlist to [uri] off the main thread. Returns null on success, or an error. */
    suspend fun exportPlaylist(playlist: Playlist, uri: Uri): String? =
        try {
            m3uImporter.exportToUri(playlist, uri)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Unknown error"
        }

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

    // Same pattern as PlayerViewModel.smartQueueError — a transient, ViewModel-driven error the
    // UI surfaces once (e.g. via a snackbar) then clears.
    private val _tagEditError = MutableStateFlow<String?>(null)
    val tagEditError: StateFlow<String?> = _tagEditError.asStateFlow()
    fun clearTagEditError() { _tagEditError.value = null }

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

    // ─── M3U folder sync ───

    fun setPlaylistSyncFolder(uri: android.net.Uri?) {
        viewModelScope.launch { playlistFolderSync.setFolder(uri) }
    }

    fun syncPlaylistFolderNow(onDone: (dev.yuwixx.resonance.data.playlist.PlaylistFolderSync.Report) -> Unit) {
        viewModelScope.launch { onDone(playlistFolderSync.syncNow()) }
    }

    fun exportAllPlaylistsToFolder(onDone: (Int) -> Unit) {
        viewModelScope.launch { onDone(playlistFolderSync.exportAll()) }
    }

    fun saveSmartPlaylist(playlistId: Long?, name: String, rules: dev.yuwixx.resonance.data.playlist.SmartRules) {
        viewModelScope.launch { playlistRepository.saveSmartPlaylist(playlistId, name, rules) }
    }

    /** Freezes a smart playlist's current songs into a normal, editable playlist. */
    fun convertSmartToStatic(playlist: Playlist) {
        viewModelScope.launch { playlistRepository.convertSmartToStatic(playlist.id, playlist.songs.map { it.id }) }
    }

    fun createPlaylistWithSongs(name: String, songIds: List<Long>) {
        viewModelScope.launch { playlistRepository.createPlaylistWithSongs(name, songIds) }
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
        onResult: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val success = musicRepository.updateSongTags(songId, title, artist, albumArtist, album, genre, year, trackNumber, discNumber)
            if (!success) _tagEditError.value = "Couldn't save tags — the file couldn't be written"
            // Tag edits only touch local files: rescan MediaStore, never the Navidrome library.
            musicRepository.syncWithMediaStore(force = true)
            onResult(success)
        }
    }

    fun batchUpdateSongTags(
        songIds: List<Long>,
        albumArtist: String?,
        album: String?,
        genre: String?,
        year: Int?,
        onComplete: (succeeded: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            val succeeded = musicRepository.batchUpdateSongTags(songIds, albumArtist, album, genre, year)
            musicRepository.syncWithMediaStore(force = true)
            onComplete(succeeded, songIds.size)
        }
    }

    fun syncLibrary(force: Boolean = false) {
        if (_isSyncing.value && !force) return
        viewModelScope.launch {
            when (prefs.musicSource.first()) {
                MusicSource.LOCAL -> {
                    _isSyncing.value = true
                    try { musicRepository.syncWithMediaStore(force) }
                    finally { _isSyncing.value = false }
                }
                MusicSource.NAVIDROME -> {
                    _isSyncing.value = true
                    try {
                        navidromeRepository.syncLibrary(force)
                        val artists = navidromeRepository.cachedArtists()
                        if (artists.isNotEmpty()) _navidromeArtists.value = artists
                        // Stars and linked playlists aren't covered by the scan status.
                        navidromeSyncRepository.pullStarredFromServer()
                        navidromeSyncRepository.pullPlaylistsFromServer()
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
            // The cached library shows immediately; the sync below is a cheap "anything new?"
            // check that only downloads/writes when the server or MediaStore actually changed.
            if (source == MusicSource.NAVIDROME) {
                val artists = navidromeRepository.cachedArtists()
                if (artists.isNotEmpty()) _navidromeArtists.value = artists
            }
            syncLibrary()
            // Pick up playlist files changed elsewhere (PC, Syncthing…) since the last run.
            if (prefs.playlistSyncFolder.first() != null) runCatching { playlistFolderSync.syncNow() }
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
