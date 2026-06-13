// Reads and writes audio metadata via MediaMetadataRetriever / MediaStore ContentValues.
// Note: physical ID3 tag writes are subject to Android scoped-storage restrictions.
package dev.yuwixx.resonance.domain.usecase

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
        @Suppress("DEPRECATION")
        val path = context.contentResolver.query(
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
        try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, tags.title)
                put(MediaStore.Audio.Media.ARTIST, tags.artist)
                put(MediaStore.Audio.Media.ALBUM_ARTIST, tags.albumArtist)
                put(MediaStore.Audio.Media.ALBUM, tags.album)
                put(MediaStore.Audio.Media.GENRE, tags.genre)
                put(MediaStore.Audio.Media.YEAR, tags.year.toIntOrNull() ?: 0)
                put(MediaStore.Audio.Media.TRACK, tags.trackNumber.toIntOrNull() ?: 0)
            }
            val updated = context.contentResolver.update(song.uri, values, null, null)
            updated > 0
        } catch (e: Exception) {
            false
        }
    }
}
