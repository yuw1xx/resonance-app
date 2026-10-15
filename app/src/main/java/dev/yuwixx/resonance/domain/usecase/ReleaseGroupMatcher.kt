// Pure "does this MusicBrainz release-group result actually match the song" decision, pulled out
// of MetadataEnrichmentScanner.checkYear so it's unit-testable without a MusicBrainzApi/DI graph.
package dev.yuwixx.resonance.domain.usecase

object ReleaseGroupMatcher {
    fun isExactMatch(
        artistCreditNames: List<String>,
        releaseGroupTitle: String,
        songArtist: String,
        songAlbum: String,
    ): Boolean =
        artistCreditNames.any { it.equals(songArtist, ignoreCase = true) } &&
            releaseGroupTitle.equals(songAlbum, ignoreCase = true)
}
