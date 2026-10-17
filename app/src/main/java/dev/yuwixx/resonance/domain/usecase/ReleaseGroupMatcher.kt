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
