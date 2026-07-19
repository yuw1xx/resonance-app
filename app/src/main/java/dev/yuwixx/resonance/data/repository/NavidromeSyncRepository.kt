// Two-way sync of liked songs and playlists with Navidrome, via the Subsonic star/unstar and
// createPlaylist/updatePlaylist/deletePlaylist endpoints. Star/unstar mutations go through a
// durable pending-action queue (modeled on LastFmRepository's pending-scrobble queue) so an
// offline like isn't lost; a pull from the server always defers to a song with a pending local
// action, so the local action wins instead of needing timestamp-based conflict resolution.
package dev.yuwixx.resonance.data.repository

import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PendingStarActionDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.database.entity.PendingStarActionEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.database.entity.StarAction
import dev.yuwixx.resonance.data.network.NavidromeApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavidromeSyncRepository @Inject constructor(
    private val navidromeApiProvider: NavidromeApiProvider,
    private val likedSongsDao: LikedSongsDao,
    private val pendingStarActionDao: PendingStarActionDao,
    private val playlistDao: PlaylistDao,
    private val navidromeSongDao: NavidromeSongDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()

    init {
        scope.launch { flushPendingStarActions() }
    }

    // ─── Likes ───

    fun star(songId: Long, navidromeId: String) {
        scope.launch {
            pendingStarActionDao.insert(
                PendingStarActionEntity(songId = songId, navidromeId = navidromeId, action = StarAction.STAR, timestamp = System.currentTimeMillis())
            )
            flushPendingStarActions()
        }
    }

    fun unstar(songId: Long, navidromeId: String) {
        scope.launch {
            pendingStarActionDao.insert(
                PendingStarActionEntity(songId = songId, navidromeId = navidromeId, action = StarAction.UNSTAR, timestamp = System.currentTimeMillis())
            )
            flushPendingStarActions()
        }
    }

    suspend fun flushPendingStarActions() {
        val api = navidromeApiProvider.currentApi() ?: return
        flushMutex.withLock {
            // Only the latest queued action per song matters — a rapid star-then-unstar only
            // needs to push the net "unstar" result, not both calls in sequence.
            val latestPerSong = pendingStarActionDao.getAll()
                .groupBy { it.songId }
                .mapValues { (_, actions) -> actions.maxBy { it.timestamp } }

            for ((songId, action) in latestPerSong) {
                try {
                    when (action.action) {
                        StarAction.STAR -> api.star(action.navidromeId)
                        StarAction.UNSTAR -> api.unstar(action.navidromeId)
                    }
                    pendingStarActionDao.deleteForSong(songId)
                } catch (e: Exception) {
                    // Leave queued — retried on the next flush (next star/unstar, or next pull).
                }
            }
        }
    }

    suspend fun pullStarredFromServer() {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            val pendingIds = pendingStarActionDao.getPendingSongIds().toSet()
            val starred = api.getStarred2().response.starred2?.song.orEmpty()
                .map { it.id.toLongOrNull() ?: stableLongId(it.id) }
                .toSet()

            navidromeSongDao.getAllSongsList().forEach { entity ->
                // A pending local action always wins over whatever the server currently says.
                if (entity.numericId in pendingIds) return@forEach
                val shouldBeLiked = entity.numericId in starred
                val isLiked = likedSongsDao.isLiked(entity.numericId) > 0
                when {
                    shouldBeLiked && !isLiked -> likedSongsDao.likeSong(
                        LikedSongEntity(songId = entity.numericId, likedAt = System.currentTimeMillis())
                    )
                    !shouldBeLiked && isLiked -> likedSongsDao.unlikeSong(entity.numericId)
                }
            }
        } catch (e: Exception) {
            // No network / server unreachable — local state stays as-is until the next pull.
        }
    }

    // ─── Playlists ───
    // Only playlists created in Resonance sync both ways (a navidromePlaylistId gets assigned
    // on first push); playlists that already exist only on the server stay in the existing
    // read-only browse view rather than being auto-imported as editable.

    suspend fun pushPlaylistCreate(playlistId: Long, name: String, songNavidromeIds: List<String>): String? {
        val api = navidromeApiProvider.currentApi() ?: return null
        return try {
            val remoteId = api.createPlaylist(name = name, songIds = songNavidromeIds).response.playlist?.id ?: return null
            val entity = playlistDao.getPlaylistById(playlistId) ?: return remoteId
            playlistDao.updatePlaylist(entity.copy(navidromePlaylistId = remoteId))
            remoteId
        } catch (e: Exception) {
            null
        }
    }

    suspend fun pushPlaylistRename(navidromePlaylistId: String, newName: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.updatePlaylist(playlistId = navidromePlaylistId, name = newName) }
    }

    suspend fun pushPlaylistAddSongs(navidromePlaylistId: String, songNavidromeIds: List<String>) {
        if (songNavidromeIds.isEmpty()) return
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.updatePlaylist(playlistId = navidromePlaylistId, songIdsToAdd = songNavidromeIds) }
    }

    suspend fun pushPlaylistRemoveSong(navidromePlaylistId: String, songNavidromeId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.updatePlaylist(playlistId = navidromePlaylistId, songIdsToRemove = listOf(songNavidromeId)) }
    }

    suspend fun pushPlaylistDelete(navidromePlaylistId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatching { api.deletePlaylist(navidromePlaylistId) }
    }

    // Reconciles only playlists that already have a local navidromePlaylistId link — does not
    // auto-import untouched server playlists as editable local ones.
    suspend fun pullPlaylistsFromServer() {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            val remotePlaylists = api.getPlaylists().response.playlists?.playlist.orEmpty()
            for (summary in remotePlaylists) {
                val local = playlistDao.getPlaylistByNavidromeId(summary.id) ?: continue
                val detail = runCatching { api.getPlaylist(summary.id).response.playlist }.getOrNull() ?: continue

                playlistDao.clearPlaylist(local.id)
                detail.entry.forEachIndexed { index, song ->
                    val songId = song.id.toLongOrNull() ?: stableLongId(song.id)
                    playlistDao.addSongToPlaylist(
                        PlaylistSongCrossRef(playlistId = local.id, songId = songId, position = index)
                    )
                }
                if (local.name != detail.name) {
                    playlistDao.updatePlaylist(local.copy(name = detail.name, dateModified = System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            // No network / server unreachable — local state stays as-is until the next pull.
        }
    }
}
