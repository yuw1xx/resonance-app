package dev.yuwixx.resonance.data.repository

import kotlinx.coroutines.CancellationException
import dev.yuwixx.resonance.data.database.dao.ArtistInfoDao
import dev.yuwixx.resonance.data.database.entity.ArtistInfoEntity
import dev.yuwixx.resonance.data.network.ApiRateLimiter
import dev.yuwixx.resonance.data.network.DiscogsApi
import dev.yuwixx.resonance.data.network.LastFmApi
import dev.yuwixx.resonance.data.network.MusicBrainzApi
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class ArtistInfo(
    val name: String,
    val formedYear: Int?,
    val dissolvedYear: Int?,
    val isActive: Boolean,
    val type: String?,
    val genres: List<String>,
    val bio: String?,
    val members: List<String>?,
    val sourceAttribution: List<String>,
)

/** Artist info from MusicBrainz, Last.fm and, when the user has set a token, Discogs. */
@Singleton
class ArtistInfoRepository @Inject constructor(
    private val musicBrainzApi: MusicBrainzApi,
    private val lastFmApi: LastFmApi,
    private val discogsApi: DiscogsApi,
    private val artistInfoDao: ArtistInfoDao,
    private val prefs: ResonancePreferences,
    private val lastFmRepository: LastFmRepository,
    @Named("musicbrainz-limiter") private val mbLimiter: ApiRateLimiter,
    @Named("discogs-limiter") private val discogsLimiter: ApiRateLimiter,
) {
    suspend fun getArtistInfo(artistName: String): ArtistInfo = withContext(Dispatchers.IO) {
        val cached = artistInfoDao.getArtistInfo(artistName)
        val cacheAgeMs = 7L * 24 * 60 * 60 * 1000
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < cacheAgeMs) {
            return@withContext cached.toArtistInfo()
        }

        // A source that fails (offline, rate limited) is different from one with no data:
        // only results where every lookup got an answer are cached.
        var lookupFailed = false
        suspend fun <T> attempt(block: suspend () -> T?): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lookupFailed = true
            null
        }

        val mb = attempt { mbLimiter.withRateLimit { fetchMusicBrainz(artistName) } }
        val lfm = attempt { fetchLastFm(artistName) }
        val token = prefs.discogsApiToken.first()
        val discogs = if (token.isNotBlank()) {
            attempt { discogsLimiter.withRateLimit { fetchDiscogs(artistName, token) } }
        } else null

        val info = ArtistInfo(
            name = artistName,
            formedYear = mb?.formedYear,
            dissolvedYear = mb?.dissolvedYear,
            isActive = mb?.dissolvedYear == null,
            type = mb?.type,
            genres = (mb?.tags.orEmpty() + lfm?.tags.orEmpty()).distinct().take(6),
            bio = lfm?.bio ?: discogs?.profile,
            members = discogs?.members,
            sourceAttribution = listOfNotNull(
                "MusicBrainz".takeIf { mb != null },
                "Last.fm".takeIf { lfm != null },
                "Discogs".takeIf { discogs != null },
            ),
        )
        if (!lookupFailed) artistInfoDao.upsert(info.toEntity())
        info
    }

    private data class MbResult(val formedYear: Int?, val dissolvedYear: Int?, val type: String?, val tags: List<String>)
    private data class LfmResult(val bio: String?, val tags: List<String>)
    private data class DiscogsResult(val profile: String?, val members: List<String>?)

    private suspend fun fetchMusicBrainz(artistName: String): MbResult? {
        val search = musicBrainzApi.searchArtist(query = "artist:\"$artistName\"", limit = 1)
        val artist = search.artists.firstOrNull() ?: return null
        val formedYear = artist.lifeSpan?.begin?.take(4)?.toIntOrNull()
        val dissolvedYear = artist.lifeSpan?.end?.take(4)?.toIntOrNull()
        val tags = artist.tags.sortedByDescending { it.count }.take(6).map { it.name }
        return MbResult(formedYear, dissolvedYear, artist.type, tags)
    }

    private suspend fun fetchLastFm(artistName: String): LfmResult? {
        val apiKey = lastFmRepository.getApiKey()
        val response = lastFmApi.getArtistInfo(artist = artistName, apiKey = apiKey)
        val artist = response.artist ?: return null
        // Strip the "Read more on Last.fm" link at the end of every bio.
        val bio = artist.bio?.summary
            ?.substringBefore("<a href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val tags = artist.tags?.tag?.map { it.name } ?: emptyList()
        return LfmResult(bio, tags)
    }

    private suspend fun fetchDiscogs(artistName: String, token: String): DiscogsResult? {
        val auth = "Discogs token=$token"
        val search = discogsApi.searchArtist(q = artistName, auth = auth)
        val result = search.results.firstOrNull() ?: return null
        val artist = discogsApi.getArtist(id = result.id, auth = auth)
        val members = artist.members?.filter { it.active }?.map { it.name }
        return DiscogsResult(artist.profile.takeIf { it.isNotBlank() }, members)
    }
}

private fun ArtistInfo.toEntity() = ArtistInfoEntity(
    artistName = name,
    formedYear = formedYear,
    dissolvedYear = dissolvedYear,
    type = type,
    genres = genres.joinToString(","),
    bio = bio,
    members = members?.joinToString(","),
    sourceAttribution = sourceAttribution.joinToString(","),
)

private fun ArtistInfoEntity.toArtistInfo() = ArtistInfo(
    name = artistName,
    formedYear = formedYear,
    dissolvedYear = dissolvedYear,
    isActive = dissolvedYear == null,
    type = type,
    genres = genres.split(",").filter { it.isNotBlank() },
    bio = bio,
    members = members?.split(",")?.filter { it.isNotBlank() },
    sourceAttribution = sourceAttribution.split(",").filter { it.isNotBlank() },
)
