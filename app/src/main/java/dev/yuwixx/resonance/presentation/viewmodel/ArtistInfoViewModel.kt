// Backs the artist info bottom sheet on ArtistDetailScreen. Fetches on demand (only once the
// sheet is actually opened) rather than prefetching like artwork does, since MusicBrainz/Discogs
// calls are rate-limited and heavier than an image fetch.
package dev.yuwixx.resonance.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.repository.ArtistInfo
import dev.yuwixx.resonance.data.repository.ArtistInfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ArtistInfoState {
    data object Idle : ArtistInfoState()
    data object Loading : ArtistInfoState()
    data class Loaded(val info: ArtistInfo) : ArtistInfoState()
    data object Error : ArtistInfoState()
}

@HiltViewModel
class ArtistInfoViewModel @Inject constructor(
    private val artistInfoRepository: ArtistInfoRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ArtistInfoState>(ArtistInfoState.Idle)
    val state: StateFlow<ArtistInfoState> = _state.asStateFlow()

    private var loadedFor: String? = null

    fun load(artistName: String) {
        if (loadedFor == artistName && _state.value !is ArtistInfoState.Error) return
        loadedFor = artistName
        _state.value = ArtistInfoState.Loading
        viewModelScope.launch {
            _state.value = try {
                ArtistInfoState.Loaded(artistInfoRepository.getArtistInfo(artistName))
            } catch (e: Exception) {
                ArtistInfoState.Error
            }
        }
    }
}
