package dev.yuwixx.resonance.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.yuwixx.resonance.data.database.entity.PlaylistEntity
import dev.yuwixx.resonance.data.database.entity.PlaylistSongCrossRef
import dev.yuwixx.resonance.data.database.entity.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistDaoTest {
    private lateinit var db: ResonanceDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ResonanceDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(id: Long, title: String) = SongEntity(
        id = id, uri = "content://media/$id", title = title, artist = "Artist",
        albumArtist = "Artist", album = "Album", albumId = 1, genre = "",
        duration = 200_000, size = 1000, bitrate = 128, sampleRate = 44100,
        trackNumber = 0, discNumber = 0, year = 2020, dateAdded = 0, dateModified = 0,
        path = "/music/$id.mp3", folder = "/music", mimeType = "audio/mpeg",
        replayGainTrack = null, replayGainAlbum = null,
    )

    @Test
    fun `reordering songs persists new position order`() = runTest {
        db.songDao().upsertSongs(listOf(song(1, "A"), song(2, "B"), song(3, "C")))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "My Playlist", artworkUri = null))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 1, 0))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 2, 1))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 3, 2))

        // Move song 3 to the front — mirrors what PlaylistDetailScreen's drag-to-reorder does:
        // reassign position = index for the whole list in its new order.
        db.playlistDao().updateSongPosition(playlistId, 3, 0)
        db.playlistDao().updateSongPosition(playlistId, 1, 1)
        db.playlistDao().updateSongPosition(playlistId, 2, 2)

        val refs = db.playlistDao().getPlaylistSongRefs(playlistId)
        assertEquals(listOf(3L, 1L, 2L), refs.map { it.songId })
    }

    @Test
    fun `removing a song from a playlist leaves the others in order`() = runTest {
        db.songDao().upsertSongs(listOf(song(1, "A"), song(2, "B"), song(3, "C")))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "P", artworkUri = null))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 1, 0))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 2, 1))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 3, 2))

        db.playlistDao().removeSongFromPlaylist(playlistId, 2)

        val refs = db.playlistDao().getPlaylistSongRefs(playlistId)
        assertEquals(listOf(1L, 3L), refs.map { it.songId })
    }

    @Test
    fun `adding a song twice replaces its position rather than duplicating`() = runTest {
        db.songDao().upsertSongs(listOf(song(1, "A"), song(2, "B")))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "P", artworkUri = null))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 1, 0))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 2, 1))

        // Re-adding song 1 at a new position (the cross-ref's primary key is
        // (playlistId, songId), so this is an upsert, not a second row).
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 1, 5))

        val refs = db.playlistDao().getPlaylistSongRefs(playlistId)
        assertEquals(2, refs.size)
        assertEquals(5, refs.first { it.songId == 1L }.position)
    }

    @Test
    fun `clearing a playlist removes all its song refs`() = runTest {
        db.songDao().upsertSongs(listOf(song(1, "A"), song(2, "B")))
        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = "P", artworkUri = null))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 1, 0))
        db.playlistDao().addSongToPlaylist(PlaylistSongCrossRef(playlistId, 2, 1))

        db.playlistDao().clearPlaylist(playlistId)

        assertEquals(emptyList<Long>(), db.playlistDao().getPlaylistSongRefs(playlistId).map { it.songId })
    }
}
