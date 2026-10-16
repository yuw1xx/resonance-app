package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.MixNavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.PlaylistSourceDao
import dev.yuwixx.resonance.data.database.dao.SmartPlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.database.entity.SmartPlaylistEntity
import dev.yuwixx.resonance.data.model.MixType
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.playlist.SmartPlaylistMatcher
import dev.yuwixx.resonance.data.playlist.SmartRules
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val mixNavidromeSongDao: MixNavidromeSongDao,
    private val prefs: ResonancePreferences,
    private val navidromeSyncRepository: NavidromeSyncRepository,
    private val smartPlaylistDao: SmartPlaylistDao,
    private val playlistSourceDao: PlaylistSourceDao,
    private val likedSongsDao: LikedSongsDao,
    private val moshi: Moshi,
) {
    private data class Library(
        val entities: List<PlaylistEntity>,
        val refs: Map<Long, List<PlaylistSongCrossRef>>,
        val localById: Map<Long, Song>,
        val navidromeByNumericId: Map<Long, Song>,
        val navidromeById: Map<String, Song>,
    )

    private val rulesAdapter by lazy { moshi.adapter(SmartRules::class.java) }

    // Whole-table flows joined in memory, so any change to playlists, their songs or the songs
    // themselves emits once.
    private val library: Flow<Library> = combine(
        playlistDao.getAllPlaylists(),
        playlistDao.observeAllSongRefs(),
        songDao.getAllSongs(),
        navidromeSongDao.getAllSongs(),
        prefs.artistDelimiter,
    ) { entities, refs, localSongs, navidromeSongs, delimiter ->
        val localById = HashMap<Long, Song>(localSongs.size * 2)
        localSongs.forEach { localById[it.id] = it.toDomain(delimiter) }
        val navidromeByNumericId = HashMap<Long, Song>(navidromeSongs.size * 2)
        val navidromeById = HashMap<String, Song>(navidromeSongs.size * 2)
        navidromeSongs.forEach {
            val song = it.toDomain()
            navidromeByNumericId[it.numericId] = song
            navidromeById[it.navidromeId] = song
        }
        Library(entities, refs.groupBy { it.playlistId }, localById, navidromeByNumericId, navidromeById)
    }

    // Smart playlists are evaluated here against the active library, so they update live with
    // rescans, play counts and likes.
    val allPlaylists: Flow<List<Playlist>> = combine(
        library,
        smartPlaylistDao.observeAll(),
        likedSongsDao.getLikedSongIds(),
        prefs.musicSource,
    ) { lib, smartEntities, likedIds, source ->
        if (lib.entities.isEmpty()) return@combine emptyList()
        val rulesById = smartEntities.mapNotNull { e ->
            runCatchingCancellable { rulesAdapter.fromJson(e.rulesJson) }.getOrNull()?.let { e.playlistId to it }
        }.toMap()
        val liked = likedIds.toHashSet()
        val activeLibrary by lazy {
            if (source == MusicSource.NAVIDROME) lib.navidromeByNumericId.values.toList() else lib.localById.values.toList()
        }
        lib.entities.map { entity ->
            val rules = rulesById[entity.id]
            val songs = when {
                rules != null -> SmartPlaylistMatcher.apply(rules, activeLibrary, liked, seed = entity.id)
                entity.isMix && entity.mixSource == "NAVIDROME" ->
                    mixNavidromeSongDao.getSongRefs(entity.id).mapNotNull { lib.navidromeById[it.navidromeSongId] }
                else -> {
                    // Songs added while Navidrome is the active source are stored by their numeric
                    // Navidrome id. Linked playlists resolve against Navidrome first so a numeric id
                    // that happens to collide with a MediaStore id doesn't show the wrong local song.
                    val preferNavidrome = entity.navidromePlaylistId != null
                    lib.refs[entity.id].orEmpty().mapNotNull { ref ->
                        if (preferNavidrome) lib.navidromeByNumericId[ref.songId] ?: lib.localById[ref.songId]
                        else lib.localById[ref.songId] ?: lib.navidromeByNumericId[ref.songId]
                    }
                }
            }
            entity.toDomain(songs).let { if (rules != null) it.copy(smartRules = rules, isReadOnly = true) else it }
        }
    }.flowOn(Dispatchers.Default)

    /** Creates a smart playlist, or updates the name and rules of an existing one. */
    suspend fun saveSmartPlaylist(playlistId: Long?, name: String, rules: SmartRules): Long {
        val id = if (playlistId == null) {
            playlistDao.insertPlaylist(PlaylistEntity(name = name, artworkUri = null))
        } else {
            playlistDao.getPlaylistById(playlistId)?.let {
                playlistDao.updatePlaylist(it.copy(name = name, dateModified = System.currentTimeMillis()))
            }
            playlistId
        }
        smartPlaylistDao.upsert(SmartPlaylistEntity(playlistId = id, rulesJson = rulesAdapter.toJson(rules)))
        return id
    }

    /** Turns a smart playlist into a normal one with its current songs. */
    suspend fun convertSmartToStatic(playlistId: Long, songIds: List<Long>) {
        smartPlaylistDao.delete(playlistId)
        playlistDao.replaceSongs(playlistId, songIds)
        updateModifiedTime(playlistId)
        syncPushReplace(playlistId)
    }

    private fun PlaylistEntity.toDomain(songs: List<Song>): Playlist = Playlist(
        id = id,
        name = name,
        songs = songs,
        isReadOnly = isReadOnly,
        artworkUri = artworkUri?.let { Uri.parse(it) },
        dateCreated = dateCreated,
        dateModified = dateModified,
        isMix = isMix,
        mixType = MixType.entries.find { it.name == mixType },
        mixSource = mixSource,
        lastMixGenerated = lastMixGenerated,
    )

    fun getPlaylistById(id: Long): Flow<Playlist?> = allPlaylists.map { playlists ->
        playlists.find { it.id == id }
    }

    suspend fun hasMixes(): Boolean = playlistDao.getMixPlaylists().isNotEmpty()

    suspend fun createPlaylist(name: String): Long =
        playlistDao.insertPlaylist(PlaylistEntity(name = name, artworkUri = null))

    suspend fun deletePlaylist(playlistId: Long) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.deletePlaylistAndSongs(entity)
        smartPlaylistDao.delete(playlistId)
        // A playlist that came from the sync folder: remember its file so the next folder sync
        // doesn't import it again.
        playlistSourceDao.getByPlaylist(playlistId)?.let { source ->
            prefs.setPlaylistSyncIgnored(prefs.playlistSyncIgnored.first() + source.documentUri)
            playlistSourceDao.delete(playlistId)
        }
        entity.navidromePlaylistId?.let { navidromeSyncRepository.pushPlaylistDelete(it) }
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = addSongsToPlaylist(playlistId, listOf(songId))

    // Returns how many songs were added; songs already in the playlist are skipped.
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>): Int {
        val start = playlistDao.nextPosition(playlistId)
        val results = playlistDao.insertSongRefsIgnoringDuplicates(
            songIds.distinct().mapIndexed { i, songId ->
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = start + i)
            }
        )
        val addedIds = songIds.distinct().filterIndexed { i, _ -> results.getOrElse(i) { -1L } != -1L }
        if (addedIds.isEmpty()) return 0
        playlistDao.compactPositions(playlistId)
        updateModifiedTime(playlistId)
        syncPushAddSongs(playlistId, addedIds)
        return addedIds.size
    }

    suspend fun createPlaylistWithSongs(name: String, songIds: List<Long>): Long {
        val id = createPlaylist(name)
        if (songIds.isNotEmpty()) addSongsToPlaylist(id, songIds)
        return id
    }

    suspend fun replacePlaylistSongs(playlistId: Long, songIds: List<Long>) {
        playlistDao.replaceSongs(playlistId, songIds)
        updateModifiedTime(playlistId)
        syncPushReplace(playlistId)
    }

    // For a Navidrome-linked playlist, sends the whole local track list to the server so the
    // next pull doesn't undo a reorder, replace or clean-up made here.
    private suspend fun syncPushReplace(playlistId: Long) {
        val linked = playlistDao.getPlaylistById(playlistId)?.navidromePlaylistId ?: return
        val ids = playlistDao.getPlaylistSongRefs(playlistId)
            .mapNotNull { navidromeSongDao.getSongByNumericId(it.songId)?.navidromeId }
        navidromeSyncRepository.pushPlaylistReplace(linked, ids)
    }

    suspend fun songIds(playlistId: Long): List<Long> =
        playlistDao.getPlaylistSongRefs(playlistId).map { it.songId }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
        playlistDao.compactPositions(playlistId)
        updateModifiedTime(playlistId)
        val linked = playlistDao.getPlaylistById(playlistId)?.navidromePlaylistId ?: return
        val navidromeId = navidromeSongDao.getSongByNumericId(songId)?.navidromeId ?: return
        navidromeSyncRepository.pushPlaylistRemoveSong(linked, navidromeId)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(entity.copy(name = newName, dateModified = System.currentTimeMillis()))
        entity.navidromePlaylistId?.let { navidromeSyncRepository.pushPlaylistRename(it, newName) }
    }

    // Only playlists created in Resonance sync both ways: the first time songs are added while
    // Navidrome is the active source, this establishes the remote link (with those songs
    // already included); every mutation after that just pushes the specific diff.
    private suspend fun syncPushAddSongs(playlistId: Long, addedSongIds: List<Long>) {
        if (prefs.musicSource.first() != MusicSource.NAVIDROME) return
        val entity = playlistDao.getPlaylistById(playlistId) ?: return

        if (entity.navidromePlaylistId == null) {
            val allSongIds = playlistDao.getPlaylistSongRefs(playlistId).map { it.songId }
            val navidromeIds = allSongIds.mapNotNull { navidromeSongDao.getSongByNumericId(it)?.navidromeId }
            navidromeSyncRepository.pushPlaylistCreate(playlistId, entity.name, navidromeIds)
        } else {
            val navidromeIds = addedSongIds.mapNotNull { navidromeSongDao.getSongByNumericId(it)?.navidromeId }
            navidromeSyncRepository.pushPlaylistAddSongs(entity.navidromePlaylistId, navidromeIds)
        }
    }

    suspend fun updatePlaylistArtwork(playlistId: Long, artworkUri: Uri?) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(
            entity.copy(artworkUri = artworkUri?.toString(), dateModified = System.currentTimeMillis())
        )
    }

    suspend fun reorderPlaylist(playlistId: Long, songs: List<Song>) {
        songs.forEachIndexed { index, song ->
            playlistDao.updateSongPosition(playlistId, song.id, index)
        }
        updateModifiedTime(playlistId)
        syncPushReplace(playlistId)
    }

    suspend fun deduplicatePlaylist(playlistId: Long): Int = withContext(Dispatchers.IO) {
        val refs = playlistDao.getPlaylistSongRefs(playlistId)
        val seen = mutableSetOf<String>()
        val toRemove = mutableListOf<Long>()
        refs.forEach { ref ->
            val song = songDao.getSongById(ref.songId) ?: return@forEach
            val key = "${song.title.trim().lowercase()}|${song.artist.trim().lowercase()}"
            if (!seen.add(key)) toRemove.add(ref.songId)
        }
        toRemove.forEach { songId -> playlistDao.removeSongFromPlaylist(playlistId, songId) }
        if (toRemove.isNotEmpty()) {
            playlistDao.compactPositions(playlistId)
            updateModifiedTime(playlistId)
            syncPushReplace(playlistId)
        }
        toRemove.size
    }

    private suspend fun updateModifiedTime(playlistId: Long) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(entity.copy(dateModified = System.currentTimeMillis()))
    }
}
