package dev.yuwixx.resonance.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.NavidromeSongDao
import dev.yuwixx.resonance.data.database.dao.PlaylistDao
import dev.yuwixx.resonance.data.database.dao.SongDao
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Playlist
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.data.repository.PlaylistRepository
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class M3uImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val navidromeSongDao: NavidromeSongDao,
    private val playlistDao: PlaylistDao,
    private val playlistRepository: PlaylistRepository,
    private val prefs: ResonancePreferences,
) {

    /** What to do when a playlist with the imported name already exists. */
    enum class ConflictMode { KEEP_BOTH, REPLACE, MERGE }

    data class Preview(val uri: Uri, val name: String, val existingPlaylistId: Long?)

    data class Result(
        val playlistId: Long,
        val name: String,
        val total: Int,
        val matched: Int,
        /** Locations (or "Artist - Title") of entries that couldn't be found in the library. */
        val unmatched: List<String>,
        val skippedRemote: Int,
    )

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Reads just enough of each file to name it and detect name clashes, so the UI can ask
     *  about conflicts once before importing. */
    suspend fun preview(uris: List<Uri>): List<Preview> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = runCatchingCancellable { readPlaylist(uri).name }.getOrNull() ?: displayNameStem(uri)
            Preview(uri, name, playlistDao.getPlaylistByName(name)?.id)
        }
    }

    suspend fun import(uri: Uri, mode: ConflictMode): Result = withContext(Dispatchers.IO) {
        val parsed = try {
            readPlaylist(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("Couldn't read ${displayNameStem(uri)}", e)
        }
        val name = parsed.name ?: displayNameStem(uri)
        val resolved = resolveEntries(parsed, baseDirFor(uri))

        val existing = playlistDao.getPlaylistByName(name)
        val playlistId = when {
            existing != null && mode == ConflictMode.REPLACE -> {
                playlistRepository.replacePlaylistSongs(existing.id, resolved.songIds)
                existing.id
            }
            existing != null && mode == ConflictMode.MERGE -> {
                playlistRepository.addSongsToPlaylist(existing.id, resolved.songIds)
                existing.id
            }
            else -> playlistRepository.createPlaylistWithSongs(uniqueName(name), resolved.songIds)
        }
        Result(playlistId, name, parsed.entries.size, resolved.songIds.size, resolved.unmatched, resolved.skippedRemote)
    }

    /** Re-imports a file into an existing playlist, replacing its contents (folder sync). */
    suspend fun reimportInto(playlistId: Long, uri: Uri): Result = withContext(Dispatchers.IO) {
        val parsed = readPlaylist(uri)
        val resolved = resolveEntries(parsed, baseDirFor(uri))
        playlistRepository.replacePlaylistSongs(playlistId, resolved.songIds)
        val name = parsed.name ?: displayNameStem(uri)
        Result(playlistId, name, parsed.entries.size, resolved.songIds.size, resolved.unmatched, resolved.skippedRemote)
    }

    suspend fun exportToUri(playlist: Playlist, uri: Uri, relativeToDir: String? = null) = withContext(Dispatchers.IO) {
        val text = M3uWriter.write(
            playlist.name,
            playlist.songs.map { M3uWriter.Track(it.path, it.duration, it.displayArtist, it.title) },
            relativeToDir,
        )
        // "wt" truncates: overwriting a longer existing file must not leave its tail behind.
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw ImportException("Couldn't open the destination file")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private data class Resolved(val songIds: List<Long>, val unmatched: List<String>, val skippedRemote: Int)

    private suspend fun resolveEntries(parsed: M3uPlaylist, baseDir: String?): Resolved {
        val matcher = SongMatcher(libraryCandidates())
        val ids = LinkedHashSet<Long>()
        val unmatched = mutableListOf<String>()
        var remote = 0
        parsed.entries.forEach { entry ->
            if (entry.isRemote) { remote++; return@forEach }
            val match = matcher.match(entry, baseDir)
            if (match != null) ids += match.candidate.id
            else unmatched += listOfNotNull(entry.artist, entry.title).joinToString(" - ").ifBlank { entry.location }
        }
        return Resolved(ids.toList(), unmatched, remote)
    }

    // Match against whichever library is active: Navidrome song paths are server-side paths,
    // which suffix matching handles just as well as local ones.
    private suspend fun libraryCandidates(): List<MatchCandidate> =
        if (prefs.musicSource.first() == MusicSource.NAVIDROME) {
            navidromeSongDao.getAllSongsList().map {
                MatchCandidate(it.numericId, it.path, it.title, it.artist, it.album, it.durationMs)
            }
        } else {
            songDao.getAllSongsList().map {
                MatchCandidate(it.id, it.path, it.title, it.artist, it.album, it.duration)
            }
        }

    private fun readPlaylist(uri: Uri): M3uPlaylist {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportException("Couldn't open ${displayNameStem(uri)}")
        if (bytes.size > MAX_FILE_BYTES) throw ImportException("${displayNameStem(uri)} is too large to be a playlist")
        return M3uParser.parse(M3uParser.decode(bytes))
    }

    private suspend fun uniqueName(base: String): String {
        if (playlistDao.getPlaylistByName(base) == null) return base
        var n = 2
        while (playlistDao.getPlaylistByName("$base ($n)") != null) n++
        return "$base ($n)"
    }

    fun displayNameStem(uri: Uri): String {
        val display = runCatchingCancellable {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported playlist"
        return display.substringBeforeLast('.').ifBlank { "Imported playlist" }
    }

    /** Absolute folder of a document on device storage, for resolving relative entries.
     *  Only the external-storage provider exposes this; elsewhere relative entries still match
     *  through path-suffix matching. */
    fun baseDirFor(uri: Uri): String? = documentPath(uri)?.substringBeforeLast('/', missingDelimiterValue = "")?.ifBlank { null }

    fun documentPath(uri: Uri): String? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = runCatchingCancellable { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        return "$root/$relative".trimEnd('/')
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}
