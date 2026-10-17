package dev.yuwixx.resonance.data.repository

import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.Song
import dev.yuwixx.resonance.data.preferences.ResonancePreferences
import dev.yuwixx.resonance.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** The songs of whichever library is active (local files or Navidrome), shared app-wide. */
@Singleton
class ActiveLibrary @Inject constructor(
    prefs: ResonancePreferences,
    musicRepository: MusicRepository,
    navidromeRepository: NavidromeRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val songs: Flow<List<Song>> = prefs.musicSource
        .flatMapLatest { source ->
            when (source) {
                MusicSource.LOCAL -> musicRepository.allSongs
                MusicSource.NAVIDROME -> navidromeRepository.cachedSongs
            }
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(10_000), replay = 1)
}
