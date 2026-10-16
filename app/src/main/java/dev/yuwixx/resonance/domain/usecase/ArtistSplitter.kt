package dev.yuwixx.resonance.domain.usecase

import java.util.concurrent.ConcurrentHashMap

/** Splits an artist tag such as "A/B" on the user's delimiter characters. */
object ArtistSplitter {
    // split() runs for every song on every library emission, so compiled patterns are cached.
    private val delimiterRegexCache = ConcurrentHashMap<String, Regex>()

    fun split(artist: String, delimiter: String): List<String> {
        val delimPattern = delimiterRegexCache.getOrPut(delimiter) {
            Regex("[${Regex.escape(delimiter)}]")
        }
        return artist.split(delimPattern)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(artist) }
    }
}
