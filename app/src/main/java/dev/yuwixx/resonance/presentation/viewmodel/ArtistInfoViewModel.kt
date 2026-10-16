package dev.yuwixx.resonance.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.repository.ArtistInfo
import dev.yuwixx.resonance.data.repository.ArtistInfoRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ArtistInfoState {
    data object Idle : ArtistInfoState()
    data object Loading : ArtistInfoState()
    data class Loaded(val info: ArtistInfo) : ArtistInfoState()
    data object Error : ArtistInfoState()
}

/** Loads artist info only when the sheet opens, since MusicBrainz and Discogs are rate-limited. */
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtistInfoState.Error
            }
        }
    }
}
