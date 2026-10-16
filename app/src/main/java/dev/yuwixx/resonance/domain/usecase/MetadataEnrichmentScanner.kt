package dev.yuwixx.resonance.domain.usecase

import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.network.ApiRateLimiter
import dev.yuwixx.resonance.data.network.LastFmApi
import dev.yuwixx.resonance.data.network.MusicBrainzApi
import dev.yuwixx.resonance.data.repository.LastFmRepository
import dev.yuwixx.resonance.data.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Proposes release years from MusicBrainz and missing genres from Last.fm. It never writes; the
 * user reviews and applies the changes.
 */
@Singleton
class MetadataEnrichmentScanner @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val lastFmApi: LastFmApi,
    private val lastFmRepository: LastFmRepository,
    @Named("musicbrainz-limiter") private val mbLimiter: ApiRateLimiter,
) {
    enum class Field { YEAR, GENRE }
    enum class Confidence { HIGH, MEDIUM, LOW }

    data class MetadataChange(
        val song: Song,
        val field: Field,
        val oldValue: String,
        val newValue: String,
        val confidence: Confidence,
        val sourceNote: String,
    )

    sealed class ScanProgress {
        data class InProgress(val index: Int, val total: Int, val estimatedRemainingMs: Long) : ScanProgress()
        data class FoundChange(val change: MetadataChange) : ScanProgress()
        data object Done : ScanProgress()
    }

    fun scan(songs: List<Song>): Flow<ScanProgress> = flow {
        // Local songs only; Navidrome files can't be tagged from here.
        val local = songs.filter { it.navidromeId == null }
        local.forEachIndexed { index, song ->
            emit(ScanProgress.InProgress(index, local.size, (local.size - index).toLong() * 1100L))
            val changes = runCatchingCancellable { mbLimiter.withRateLimit { checkSong(song) } }.getOrNull().orEmpty()
            changes.forEach { emit(ScanProgress.FoundChange(it)) }
        }
        emit(ScanProgress.Done)
    }

    private suspend fun checkSong(song: Song): List<MetadataChange> {
        val changes = mutableListOf<MetadataChange>()
        checkYear(song)?.let { changes.add(it) }
        if (song.genre.isBlank()) checkGenre(song)?.let { changes.add(it) }
        return changes
    }

    private suspend fun checkYear(song: Song): MetadataChange? {
        if (song.artist.isBlank()) return null
        val query = "artist:\"${song.artist}\" AND release:\"${song.album.ifBlank { song.title }}\""
        val response = runCatchingCancellable { musicBrainzApi.searchReleaseGroup(query = query, limit = 1) }.getOrNull() ?: return null
        val match = response.releaseGroups.firstOrNull() ?: return null
        val year = match.firstReleaseDate?.take(4)?.toIntOrNull() ?: return null
        if (year == song.year) return null

        val exactMatch = ReleaseGroupMatcher.isExactMatch(
            artistCreditNames = match.artistCredit.map { it.name },
            releaseGroupTitle = match.title,
            songArtist = song.artist,
            songAlbum = song.album,
        )

        return MetadataChange(
            song = song,
            field = Field.YEAR,
            oldValue = if (song.year > 0) song.year.toString() else "Unknown",
            newValue = year.toString(),
            confidence = if (exactMatch) Confidence.HIGH else Confidence.MEDIUM,
            sourceNote = "MusicBrainz release group \"${match.title}\"",
        )
    }

    private suspend fun checkGenre(song: Song): MetadataChange? {
        if (song.artist.isBlank()) return null
        val apiKey = lastFmRepository.getApiKey()
        val response = runCatchingCancellable { lastFmApi.getArtistInfo(artist = song.artist, apiKey = apiKey) }.getOrNull() ?: return null
        val genre = response.artist?.tags?.tag?.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return null
        return MetadataChange(
            song = song,
            field = Field.GENRE,
            oldValue = "Unknown",
            newValue = genre.replaceFirstChar { it.uppercase() },
            confidence = Confidence.LOW,
            sourceNote = "Last.fm top tag for ${song.artist}",
        )
    }
}
