package dev.yuwixx.resonance.data.repository

import android.util.Log
import dev.yuwixx.resonance.data.database.dao.LikedSongsDao
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PendingStarActionDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.entity.LikedSongEntity
import dev.yuwixx.resonance.data.database.entity.PendingStarActionEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.database.entity.StarAction
import dev.yuwixx.resonance.data.network.NavidromeApiProvider
import dev.yuwixx.resonance.data.network.ok
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Syncs likes and playlists with Navidrome. Star changes go through a pending-action queue so
 * offline likes aren't lost, and a pull never overrides a song that still has a pending action.
 */
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

    fun star(songId: Long, navidromeId: String) {
        scope.launch {
            pendingStarActionDao.insert(
                PendingStarActionEntity(
                    songId = songId,
                    navidromeId = navidromeId,
                    action = StarAction.STAR,
                    timestamp = System.currentTimeMillis(),
                )
            )
            flushPendingStarActions()
        }
    }

    fun unstar(songId: Long, navidromeId: String) {
        scope.launch {
            pendingStarActionDao.insert(
                PendingStarActionEntity(
                    songId = songId,
                    navidromeId = navidromeId,
                    action = StarAction.UNSTAR,
                    timestamp = System.currentTimeMillis(),
                )
            )
            flushPendingStarActions()
        }
    }

    suspend fun flushPendingStarActions() {
        val api = navidromeApiProvider.currentApi() ?: return
        flushMutex.withLock {
            // Only the latest action per song is pushed; star then unstar just sends the unstar.
            val latestPerSong = pendingStarActionDao.getAll()
                .groupBy { it.songId }
                .mapValues { (_, actions) -> actions.maxBy { it.timestamp } }

            for ((songId, action) in latestPerSong) {
                try {
                    when (action.action) {
                        StarAction.STAR -> api.star(action.navidromeId).ok
                        StarAction.UNSTAR -> api.unstar(action.navidromeId).ok
                    }
                    // A toggle made while this request was in flight stays queued.
                    pendingStarActionDao.deleteForSongUpTo(songId, action.timestamp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Stays queued for the next flush.
                }
            }
        }
    }

    suspend fun pullStarredFromServer() {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            val pendingIds = pendingStarActionDao.getPendingSongIds().toSet()
            val starred = api.getStarred2().ok.starred2?.song.orEmpty()
                .map { it.id.toLongOrNull() ?: stableLongId(it.id) }
                .toSet()

            // One read of the liked set, then only the differences are written.
            val liked = likedSongsDao.getAllLikedIds().toHashSet()
            val now = System.currentTimeMillis()
            navidromeSongDao.getAllSongsList().forEach { entity ->
                // A pending local action always wins over whatever the server currently says.
                if (entity.numericId in pendingIds) return@forEach
                val shouldBeLiked = entity.numericId in starred
                val isLiked = entity.numericId in liked
                when {
                    shouldBeLiked && !isLiked -> likedSongsDao.likeSong(LikedSongEntity(songId = entity.numericId, likedAt = now))
                    !shouldBeLiked && isLiked -> likedSongsDao.unlikeSong(entity.numericId)
                }
            }
        } catch (e: Exception) {
            // Server unreachable: keep the local state until the next pull.
        }
    }

    // Only playlists created in Resonance sync both ways (a navidromePlaylistId gets assigned
    // on first push); playlists that already exist only on the server stay in the existing
    // read-only browse view rather than being auto-imported as editable.

    suspend fun pushPlaylistCreate(playlistId: Long, name: String, songNavidromeIds: List<String>): String? {
        val api = navidromeApiProvider.currentApi() ?: return null
        return try {
            val remoteId = api.createPlaylist(name = name, songIds = songNavidromeIds).ok.playlist?.id ?: return null
            val entity = playlistDao.getPlaylistById(playlistId) ?: return remoteId
            playlistDao.updatePlaylist(entity.copy(navidromePlaylistId = remoteId))
            remoteId
        } catch (e: Exception) {
            null
        }
    }

    /** Replaces the server playlist's tracks with [songNavidromeIds], in this order. */
    suspend fun pushPlaylistReplace(navidromePlaylistId: String, songNavidromeIds: List<String>) {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            api.createPlaylist(playlistId = navidromePlaylistId, songIds = songNavidromeIds).ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NavidromeSync", "Couldn't update playlist $navidromePlaylistId on the server", e)
        }
    }

    suspend fun pushPlaylistRename(navidromePlaylistId: String, newName: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatchingCancellable { api.updatePlaylist(playlistId = navidromePlaylistId, name = newName) }
    }

    suspend fun pushPlaylistAddSongs(navidromePlaylistId: String, songNavidromeIds: List<String>) {
        if (songNavidromeIds.isEmpty()) return
        val api = navidromeApiProvider.currentApi() ?: return
        runCatchingCancellable { api.updatePlaylist(playlistId = navidromePlaylistId, songIdsToAdd = songNavidromeIds) }
    }

    suspend fun pushPlaylistRemoveSong(navidromePlaylistId: String, songNavidromeId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatchingCancellable { api.updatePlaylist(playlistId = navidromePlaylistId, songIdsToRemove = listOf(songNavidromeId)) }
    }

    suspend fun pushPlaylistDelete(navidromePlaylistId: String) {
        val api = navidromeApiProvider.currentApi() ?: return
        runCatchingCancellable { api.deletePlaylist(navidromePlaylistId) }
    }

    // Only playlists already linked to Navidrome; server-only playlists aren't imported.
    suspend fun pullPlaylistsFromServer() {
        val api = navidromeApiProvider.currentApi() ?: return
        try {
            val remotePlaylists = api.getPlaylists().ok.playlists?.playlist.orEmpty()
            for (summary in remotePlaylists) {
                val local = playlistDao.getPlaylistByNavidromeId(summary.id) ?: continue
                val detail = runCatchingCancellable { api.getPlaylist(summary.id).ok.playlist }.getOrNull() ?: continue

                // Rewrite the local copy only when the server's track list actually differs.
                val remoteIds = detail.entry.map { it.id.toLongOrNull() ?: stableLongId(it.id) }
                val localIds = playlistDao.getPlaylistSongRefs(local.id).map { it.songId }
                if (remoteIds != localIds) {
                    playlistDao.clearPlaylist(local.id)
                    remoteIds.forEachIndexed { index, songId ->
                        playlistDao.addSongToPlaylist(
                            PlaylistSongCrossRef(playlistId = local.id, songId = songId, position = index)
                        )
                    }
                }
                if (local.name != detail.name) {
                    playlistDao.updatePlaylist(local.copy(name = detail.name, dateModified = System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            // Server unreachable: keep the local state until the next pull.
        }
    }
}
