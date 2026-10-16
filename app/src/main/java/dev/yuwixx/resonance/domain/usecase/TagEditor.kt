// Reads audio metadata via MediaMetadataRetriever and writes it via the shared AudioTagWriter
// (jaudiotagger direct file write) — the same path MusicRepository's Tag Editor screen and
// batch tag editing already use, so a fix or scoped-storage improvement to one benefits all
// three call sites instead of drifting across two separate write implementations.
package dev.yuwixx.resonance.domain.usecase

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val audioTagWriter: AudioTagWriter,
) {
    data class SongTags(
        val title: String,
        val artist: String,
        val albumArtist: String,
        val album: String,
        val genre: String,
        val year: String,
        val trackNumber: String,
        val discNumber: String,
        val comment: String,
        val lyrics: String,
    )

    suspend fun readTags(song: Song): SongTags = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            SongTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.title,
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: song.artist,
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: song.albumArtist,
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: song.album,
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: song.genre,
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: song.year.toString(),
                trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER) ?: song.trackNumber.toString(),
                discNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER) ?: song.discNumber.toString(),
                comment = "",
                lyrics = "",
            )
        } finally {
            retriever.release()
        }
    }

    suspend fun readEmbeddedLyrics(uri: Uri): String? = withContext(Dispatchers.IO) {
        // Downloaded copies (file://) are read directly; MediaStore items need their path.
        @Suppress("DEPRECATION")
        val path = if (uri.scheme == "file") uri.path else context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@withContext null

        try {
            val lyrics = AudioFileIO.read(File(path)).tag?.getFirst(FieldKey.LYRICS)
            lyrics?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun writeTags(song: Song, tags: SongTags): Boolean = withContext(Dispatchers.IO) {
        val fields = mapOf(
            FieldKey.TITLE to tags.title,
            FieldKey.ARTIST to tags.artist,
            FieldKey.ALBUM_ARTIST to tags.albumArtist,
            FieldKey.ALBUM to tags.album,
            FieldKey.GENRE to tags.genre,
            FieldKey.YEAR to tags.year,
            FieldKey.TRACK to tags.trackNumber,
            FieldKey.DISC_NO to tags.discNumber,
        )
        val ok = audioTagWriter.writeTags(song.path, song.uri, fields)
        if (ok) {
            songDao.getSongById(song.id)?.let { entity ->
                songDao.upsertSongs(listOf(
                    entity.copy(
                        title = tags.title,
                        artist = tags.artist,
                        albumArtist = tags.albumArtist,
                        album = tags.album,
                        genre = tags.genre,
                        year = tags.year.toIntOrNull() ?: entity.year,
                        trackNumber = tags.trackNumber.toIntOrNull() ?: entity.trackNumber,
                        discNumber = tags.discNumber.toIntOrNull() ?: entity.discNumber,
                    )
                ))
            }
        }
        ok
    }
}
