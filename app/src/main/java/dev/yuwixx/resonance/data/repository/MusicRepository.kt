// Single source of truth for the local music library: MediaStore sync, domain-model flows,
// smart queue generation, listen history, and in-place tag editing via jaudiotagger.
package dev.yuwixx.resonance.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.database.dao.*
import dev.yuwixx.resonance.data.database.entity.*
import dev.yuwixx.resonance.data.model.*
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.domain.usecase.ArtistSplitter
import dev.yuwixx.resonance.domain.usecase.AudioTagWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// Compiled once and reused by naturalCompare() below — it's the comparator for every pairwise
// comparison during an Albums/Artists sort, so recompiling it per call adds up on large libraries.
private val NATURAL_SORT_CHUNK_REGEX = Regex("(\\d+|\\D+)")

@Singleton
class MusicRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val prefs: ResonancePreferences,
    private val audioTagWriter: AudioTagWriter,
    @dev.yuwixx.resonance.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    // The library flows below are shared: one mapping/grouping pass per change for every
    // collector (player, library screens, home, search…), replayed to late subscribers, and
    // kept alive briefly across screen changes. distinctUntilChanged drops Room re-emissions
    // whose result didn't actually change.
    private fun <T> Flow<T>.shared(): Flow<T> = distinctUntilChanged()
        .shareIn(appScope, SharingStarted.WhileSubscribed(10_000), replay = 1)

    // combine() with 5 flows so that changing any pref instantly re-filters/re-maps songs
    // without re-scanning MediaStore.
    val allSongs: Flow<List<Song>> = combine(
        songDao.getAllSongs(),
        prefs.artistDelimiter,
        prefs.excludedFolders,
        prefs.includedFolders,
        prefs.showFilenameAsTitle,
    ) { entities, delimiter, excluded, included, showFilename ->
        entities
            .filter { entity ->
                (included.isEmpty() || included.any { entity.folder.startsWith(it) }) &&
                excluded.none { entity.folder.startsWith(it) }
            }
            .map { entity ->
                val song = entity.toDomain(delimiter)
                if (showFilename) song.copy(title = java.io.File(song.path).nameWithoutExtension) else song
            }
    }.flowOn(Dispatchers.Default).shared()

    val allFolders: Flow<List<String>> = songDao.getAllFolders()

    val allGenres: Flow<List<String>> = songDao.getAllGenres()

    val allAlbums: Flow<List<Album>> = combine(
        allSongs,
        prefs.groupByAlbumArtist,
        prefs.ignoreArticles
    ) { songs, groupByAlbumArtist, ignoreArticles ->
        songs.groupBy {
            if (groupByAlbumArtist && it.albumArtist.isNotBlank()) "${it.albumArtist}_${it.album}"
            else it.albumId.toString()
        }.map { (_, albumSongs) ->
            val first = albumSongs.first()
            Album(
                id = first.albumId,
                title = first.album,
                artist = first.albumArtist.ifEmpty { first.displayArtist },
                year = first.year,
                songCount = albumSongs.size,
                artworkUri = first.artworkUri,
                songs = albumSongs.sortedWith(compareBy({ it.discNumber }, { it.trackNumber })),
            )
        }.sortedWith(Comparator { a, b ->
            naturalCompare(a.title.stripArticles(ignoreArticles), b.title.stripArticles(ignoreArticles))
        })
    }.flowOn(Dispatchers.Default).shared()

    val allArtists: Flow<List<Artist>> = combine(
        allSongs,
        prefs.ignoreArticles
    ) { songs, ignoreArticles ->
        val artistMap = mutableMapOf<String, MutableList<Song>>()
        songs.forEach { song ->
            song.artists.forEach { artist ->
                artistMap.getOrPut(artist) { mutableListOf() }.add(song)
            }
        }
        artistMap.map { (name, artistSongs) ->
            val albums = artistSongs.map { it.album }.distinct()
            Artist(
                name = name,
                songCount = artistSongs.size,
                albumCount = albums.size,
                songs = artistSongs,
            )
        }.sortedWith(Comparator { a, b ->
            naturalCompare(a.name.stripArticles(ignoreArticles), b.name.stripArticles(ignoreArticles))
        })
    }.flowOn(Dispatchers.Default).shared()

    private fun String.stripArticles(ignore: Boolean): String {
        if (!ignore) return this
        val lower = this.lowercase()
        return when {
            lower.startsWith("the ") -> this.substring(4)
            lower.startsWith("a ") -> this.substring(2)
            lower.startsWith("an ") -> this.substring(3)
            else -> this
        }.trim()
    }

    fun searchSongs(query: String): Flow<List<Song>> =
        combine(songDao.searchSongs(query), prefs.artistDelimiter) { entities, delimiter ->
            entities.map { it.toDomain(delimiter) }
        }.flowOn(Dispatchers.Default)

    fun getSongsByFolder(folder: String): Flow<List<Song>> =
        combine(songDao.getSongsByFolder(folder), prefs.artistDelimiter) { entities, delimiter ->
            entities.map { it.toDomain(delimiter) }
        }.flowOn(Dispatchers.Default)

    fun getSongsByGenre(genre: String): Flow<List<Song>> =
        combine(songDao.getSongsByGenre(genre), prefs.artistDelimiter) { entities, delimiter ->
            entities.map { it.toDomain(delimiter) }
        }.flowOn(Dispatchers.Default)

    // ─── MediaStore Sync ───

    /**
     * Brings the songs table in line with MediaStore. Cheap when nothing changed: the scan is
     * skipped entirely if MediaStore's generation (API 30+) and the duration filter match the
     * last sync, and otherwise only new/changed rows are written — unchanged rows aren't touched,
     * so collectors don't re-emit and play counts / ReplayGain survive.
     */
    suspend fun syncWithMediaStore(force: Boolean = false) = withContext(Dispatchers.IO) {
        val minDuration = prefs.minTrackDurationMs.first()
        val stamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "${MediaStore.getVersion(context)}:${MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)}:$minDuration"
        } else null
        if (!force && stamp != null && stamp == prefs.mediaStoreSyncStamp.first() && songDao.getAllSongIds().isNotEmpty()) {
            return@withContext
        }
        val foundIds = mutableListOf<Long>()
        val entities = mutableListOf<SongEntity>()
        var querySucceeded = false

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            "bitrate",
            "samplerate",
            "genre",
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(minDuration.toString())

        try {
            context.contentResolver.query(
                collection, projection, selection, selectionArgs, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumArtistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val bitrateCol = cursor.getColumnIndex("bitrate")
                val sampleRateCol = cursor.getColumnIndex("samplerate")
                val genreCol = cursor.getColumnIndex("genre")

                querySucceeded = true
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val folder = path.substringBeforeLast("/", "")
                    val trackRaw = cursor.getInt(trackCol)

                    foundIds.add(id)
                    entities.add(
                        SongEntity(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            title = cursor.getString(titleCol) ?: "Unknown",
                            artist = cursor.getString(artistCol) ?: "Unknown Artist",
                            albumArtist = cursor.getString(albumArtistCol) ?: "",
                            album = cursor.getString(albumCol) ?: "Unknown Album",
                            albumId = cursor.getLong(albumIdCol),
                            genre = if (genreCol >= 0) cursor.getString(genreCol) ?: "" else "",
                            duration = cursor.getLong(durationCol),
                            size = cursor.getLong(sizeCol),
                            bitrate = (if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0) / 1000,
                            sampleRate = if (sampleRateCol >= 0) cursor.getInt(sampleRateCol) else 0,
                            // MediaStore encodes disc number in the upper 12 bits of the TRACK field.
                            trackNumber = trackRaw % 1000,
                            discNumber = trackRaw / 1000,
                            year = cursor.getInt(yearCol),
                            dateAdded = cursor.getLong(dateAddedCol) * 1000L,
                            dateModified = cursor.getLong(dateModifiedCol) * 1000L,
                            path = path,
                            folder = folder,
                            mimeType = cursor.getString(mimeCol) ?: "",
                            replayGainTrack = null,
                            replayGainAlbum = null,
                        )
                    )
                }
            }

            if (!querySucceeded) return@withContext
            val existing = songDao.getSyncKeys().associateBy { it.id }
            val changed = entities.mapNotNull { entity ->
                val old = existing[entity.id] ?: return@mapNotNull entity
                val fileUnchanged = old.dateModified == entity.dateModified &&
                    old.size == entity.size && old.path == entity.path
                if (fileUnchanged) null
                else entity.copy(
                    listenCount = old.listenCount,
                    lastListened = old.lastListened,
                )
            }
            if (changed.isNotEmpty()) songDao.upsertSongs(changed)
            if (foundIds.isEmpty()) {
                songDao.deleteAllSongs()
            } else {
                val idsToDelete = existing.keys - foundIds.toHashSet()
                idsToDelete.chunked(900).forEach { chunk -> songDao.deleteSongsByIds(chunk) }
            }
            if (stamp != null) prefs.setMediaStoreSyncStamp(stamp)
        } catch (e: Exception) {
            Log.e("MusicRepository", "Sync failed", e)
        }
    }

    // ─── Listen History ───

    suspend fun recordListen(songId: Long, durationListened: Long, totalDuration: Long) {
        if (!prefs.historyEnabled.first()) return

        val minSeconds = prefs.minListenSeconds.first() * 1000L
        val minPct = prefs.minListenPercentage.first()
        val pct = if (totalDuration > 0) durationListened.toFloat() / totalDuration else 0f

        if (durationListened >= minSeconds || pct >= minPct) {
            historyDao.insertHistory(
                HistoryEntity(
                    songId = songId,
                    listenedAt = System.currentTimeMillis(),
                    durationListened = durationListened,
                    percentageListened = pct,
                )
            )
            songDao.incrementListenCount(songId, System.currentTimeMillis())

            val maxItems = prefs.maxHistoryItems.first()
            historyDao.trimHistory(maxItems)
        }
    }

    suspend fun getMostPlayedSongs(limit: Int = 50): List<Song> =
        songDao.getMostPlayed(limit).map { it.toDomain() }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDao.clearAllHistory()
    }

    // ─── Smart Queue ───

    suspend fun generateSmartQueue(
        seedSong: Song,
        reason: SmartQueueReason,
        limit: Int = 25,
    ): SmartQueueResult = withContext(Dispatchers.Default) {
        val allSongs = songDao.getMostPlayed(500).map { it.toDomain() }

        val result = when (reason) {
            SmartQueueReason.RELATED_BY_HISTORY -> {
                allSongs.filter { s ->
                    s.id != seedSong.id &&
                            (s.artists.any { it in seedSong.artists } || s.genre == seedSong.genre)
                }.sortedByDescending { it.listenCount }.take(limit)
            }
            SmartQueueReason.SIMILAR_RELEASE_DATE -> {
                val range = 2
                allSongs.filter { s ->
                    s.id != seedSong.id && seedSong.year > 0 &&
                            s.year in (seedSong.year - range)..(seedSong.year + range)
                }.sortedByDescending { it.listenCount }.take(limit)
            }
            SmartQueueReason.SAME_GENRE -> {
                allSongs.filter { s ->
                    s.id != seedSong.id && s.genre == seedSong.genre && s.genre.isNotEmpty()
                }.sortedByDescending { it.listenCount }.take(limit)
            }
            SmartQueueReason.SAME_ERA -> {
                val decade = seedSong.year / 10
                allSongs.filter { s ->
                    s.id != seedSong.id && seedSong.year > 0 && s.year > 0 && s.year / 10 == decade
                }.sortedByDescending { it.listenCount }.take(limit)
            }
            SmartQueueReason.RANDOM_DISCOVERY -> {
                allSongs.filter { it.id != seedSong.id }.shuffled().take(limit)
            }
            SmartQueueReason.MOST_PLAYED -> {
                allSongs.filter { it.id != seedSong.id }
                    .sortedByDescending { it.listenCount }.take(limit)
            }
            SmartQueueReason.LOST_MEMORIES -> {
                // Find tracks listened to on roughly this calendar day in a previous year.
                val now = System.currentTimeMillis()
                val oneYearAgoMs = now - 365L * 24 * 60 * 60 * 1000L

                val cal = Calendar.getInstance()
                val todayDayOfYear = cal.get(Calendar.DAY_OF_YEAR)
                val daysInYear = cal.getActualMaximum(Calendar.DAY_OF_YEAR)

                val history = historyDao.getAllHistory()
                val memorySongIds = history
                    .filter { entry ->
                        if (entry.listenedAt >= oneYearAgoMs) return@filter false
                        cal.timeInMillis = entry.listenedAt
                        val entryDay = cal.get(Calendar.DAY_OF_YEAR)
                        val diff = kotlin.math.abs(entryDay - todayDayOfYear)
                        diff <= 7 || diff >= daysInYear - 7
                    }
                    .map { it.songId }
                    .distinct()

                allSongs.filter { it.id in memorySongIds }.take(limit)
            }
            else -> allSongs.shuffled().take(limit)
        }

        SmartQueueResult(songs = result, reason = reason)
    }

    // Returns whether the tags were actually saved. The physical file write is attempted first
    // and the DB is only updated to match on success — previously the DB was updated
    // unconditionally and a failed write was silently discarded, so a failure looked like success
    // until the next library sync silently reverted the DB back to the real (unedited) file, with
    // no feedback to the user about what happened.
    suspend fun updateSongTags(
        songId: Long,
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        discNumber: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val entity = songDao.getSongById(songId) ?: return@withContext false

        val success = audioTagWriter.writeTags(
            path = entity.path,
            uri = Uri.parse(entity.uri),
            fields = mapOf(
                org.jaudiotagger.tag.FieldKey.TITLE to title,
                org.jaudiotagger.tag.FieldKey.ARTIST to artist,
                org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST to albumArtist,
                org.jaudiotagger.tag.FieldKey.ALBUM to album,
                org.jaudiotagger.tag.FieldKey.GENRE to genre,
                org.jaudiotagger.tag.FieldKey.YEAR to year.toString(),
                org.jaudiotagger.tag.FieldKey.TRACK to trackNumber.toString(),
                org.jaudiotagger.tag.FieldKey.DISC_NO to discNumber.toString(),
            ),
        )
        if (success) {
            songDao.upsertSongs(listOf(entity.copy(
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = genre,
                year = year,
                trackNumber = trackNumber,
                discNumber = discNumber,
            )))
        }
        success
    }

    // Applies only the fields the caller passes non-null — a multi-song batch edit where each
    // field is independently opt-in, unlike updateSongTags (single song, every field always
    // overwritten with the form's current value). Returns how many files were actually rewritten
    // on disk; the DB is only updated for songs whose physical write succeeded (previously the DB
    // was updated for every song regardless of per-song write success, same silent-divergence
    // issue as updateSongTags had).
    suspend fun batchUpdateSongTags(
        songIds: List<Long>,
        albumArtist: String?,
        album: String?,
        genre: String?,
        year: Int?,
    ): Int = withContext(Dispatchers.IO) {
        var succeeded = 0
        val updatedEntities = mutableListOf<SongEntity>()

        for (songId in songIds) {
            val entity = songDao.getSongById(songId) ?: continue

            val fields = buildMap {
                if (albumArtist != null) put(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, albumArtist)
                if (album != null) put(org.jaudiotagger.tag.FieldKey.ALBUM, album)
                if (genre != null) put(org.jaudiotagger.tag.FieldKey.GENRE, genre)
                if (year != null) put(org.jaudiotagger.tag.FieldKey.YEAR, year.toString())
            }
            if (audioTagWriter.writeTags(entity.path, Uri.parse(entity.uri), fields)) {
                succeeded++
                updatedEntities.add(
                    entity.copy(
                        albumArtist = albumArtist ?: entity.albumArtist,
                        album = album ?: entity.album,
                        genre = genre ?: entity.genre,
                        year = year ?: entity.year,
                    )
                )
            }
        }

        if (updatedEntities.isNotEmpty()) songDao.upsertSongs(updatedEntities)
        succeeded
    }

    suspend fun updateReplayGain(songId: Long, trackGain: Float?, albumGain: Float?) {
        songDao.updateReplayGain(songId, trackGain, albumGain)
    }

    // ─── Utilities ───

    // Sorts strings with embedded numbers numerically ("Track 2" before "Track 10").
    private fun naturalCompare(a: String, b: String): Int {
        val chunksA = NATURAL_SORT_CHUNK_REGEX.findAll(a).map { it.value }.toList()
        val chunksB = NATURAL_SORT_CHUNK_REGEX.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(chunksA.size, chunksB.size)) {
            val ca = chunksA[i]; val cb = chunksB[i]
            val diff = if (ca[0].isDigit() && cb[0].isDigit()) {
                ca.toLong().compareTo(cb.toLong())
            } else ca.compareTo(cb, ignoreCase = true)
            if (diff != 0) return diff
        }
        return chunksA.size - chunksB.size
    }
}

fun SongEntity.toDomain(artistDelimiter: String = ",;/&"): Song {
    val artworkUri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"), albumId
    )
    val artists = ArtistSplitter.split(artist, artistDelimiter)

    return Song(
        id = id,
        uri = Uri.parse(uri),
        title = title,
        artist = artist,
        artists = artists,
        albumArtist = albumArtist,
        album = album,
        albumId = albumId,
        genre = genre,
        duration = duration,
        size = size,
        bitrate = bitrate,
        sampleRate = sampleRate,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        dateAdded = dateAdded,
        dateModified = dateModified,
        path = path,
        folder = folder,
        mimeType = mimeType,
        replayGainTrack = replayGainTrack,
        replayGainAlbum = replayGainAlbum,
        artworkUri = artworkUri,
        listenCount = listenCount,
        lastListened = lastListened,
    )
}