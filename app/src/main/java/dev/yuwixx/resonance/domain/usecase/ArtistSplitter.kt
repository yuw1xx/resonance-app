// Splits a raw "artist" tag string (e.g. "ArtistA/ArtistB") into individual artist names using
// a user-configurable set of delimiter characters. Pulled out of SongEntity.toDomain() — which
// also touches android.net.Uri/ContentUris and so can't run in a plain JVM unit test — so this
// piece is unit-testable on its own.
package dev.yuwixx.resonance.domain.usecase

import java.util.concurrent.ConcurrentHashMap

object ArtistSplitter {
    // The delimiter set is small and user-configurable (changed rarely, in Settings), but split()
    // is called once per song on every library emission — caching the compiled pattern avoids
    // recompiling the same regex thousands of times per emission for a large library.
    // ConcurrentHashMap since this can be called concurrently from Dispatchers.Default.
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
