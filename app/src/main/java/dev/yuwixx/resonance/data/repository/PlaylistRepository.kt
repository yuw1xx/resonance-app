package dev.yuwixx.resonance.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.MixNavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.model.MixType
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val mixNavidromeSongDao: MixNavidromeSongDao,
) {
    val allPlaylists: Flow<List<Playlist>> = playlistDao.getAllPlaylists().flatMapLatest { entities ->
        if (entities.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val flows = entities.map { entity ->
            flow {
                val songs = loadSongsForPlaylist(entity)
                emit(entity.toDomain(songs))
            }
        }
        combine(flows) { it.toList() }
    }

    private suspend fun loadSongsForPlaylist(entity: PlaylistEntity): List<Song> =
        if (entity.isMix && entity.mixSource == "NAVIDROME") {
            mixNavidromeSongDao.getSongRefs(entity.id)
                .mapNotNull { ref -> navidromeSongDao.getSongById(ref.navidromeSongId)?.toDomain() }
        } else {
            playlistDao.getPlaylistSongRefs(entity.id)
                .mapNotNull { ref -> songDao.getSongById(ref.songId)?.toDomain() }
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
        playlistDao.deletePlaylist(entity)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val refs = playlistDao.getPlaylistSongRefs(playlistId)
        playlistDao.addSongToPlaylist(
            PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = refs.size)
        )
        updateModifiedTime(playlistId)
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val refs = playlistDao.getPlaylistSongRefs(playlistId)
        var nextPos = refs.size
        songIds.forEach { songId ->
            playlistDao.addSongToPlaylist(
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = nextPos++)
            )
        }
        updateModifiedTime(playlistId)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
        updateModifiedTime(playlistId)
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(entity.copy(name = newName, dateModified = System.currentTimeMillis()))
    }

    fun exportPlaylistAsM3U(playlist: Playlist): String {
        val sb = StringBuilder()
        sb.appendLine("#EXTM3U")
        sb.appendLine("#PLAYLIST:${playlist.name}")
        playlist.songs.forEach { song ->
            val durationSec = (song.duration / 1000).toInt()
            sb.appendLine("#EXTINF:$durationSec,${song.artist} - ${song.title}")
            sb.appendLine(song.path)
        }
        return sb.toString().trimEnd()
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
        if (toRemove.isNotEmpty()) updateModifiedTime(playlistId)
        toRemove.size
    }

    private suspend fun updateModifiedTime(playlistId: Long) {
        val entity = playlistDao.getPlaylistById(playlistId) ?: return
        playlistDao.updatePlaylist(entity.copy(dateModified = System.currentTimeMillis()))
    }
}
