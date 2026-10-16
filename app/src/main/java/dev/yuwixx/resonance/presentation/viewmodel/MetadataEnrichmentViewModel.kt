package dev.yuwixx.resonance.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.repository.MusicRepository
import dev.yuwixx.resonance.domain.usecase.MetadataEnrichmentScanner
import dev.yuwixx.resonance.domain.usecase.TagEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class EnrichmentUiState {
    data object Idle : EnrichmentUiState()
    data class Scanning(val index: Int, val total: Int, val estimatedRemainingMs: Long, val foundSoFar: Int) : EnrichmentUiState()
    data class Reviewing(val changes: List<MetadataEnrichmentScanner.MetadataChange>) : EnrichmentUiState()
    data object Applying : EnrichmentUiState()
    data class Done(val updated: Int, val failed: Int, val skipped: Int) : EnrichmentUiState()
}

/** Scan, review, apply. Nothing is written until the user approves it in the review step. */
@HiltViewModel
class MetadataEnrichmentViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val scanner: MetadataEnrichmentScanner,
    private val tagEditor: TagEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EnrichmentUiState>(EnrichmentUiState.Idle)
    val uiState: StateFlow<EnrichmentUiState> = _uiState.asStateFlow()

    private val _checkedIndices = MutableStateFlow<Set<Int>>(emptySet())
    val checkedIndices: StateFlow<Set<Int>> = _checkedIndices.asStateFlow()

    private var allChanges = mutableListOf<MetadataEnrichmentScanner.MetadataChange>()
    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        allChanges = mutableListOf()
        _checkedIndices.value = emptySet()
        _uiState.value = EnrichmentUiState.Scanning(0, 0, 0L, 0)
        scanJob = viewModelScope.launch {
            val songs = musicRepository.allSongs.first()
            scanner.scan(songs).collect { progress ->
                when (progress) {
                    is MetadataEnrichmentScanner.ScanProgress.InProgress -> {
                        _uiState.value = EnrichmentUiState.Scanning(
                            index = progress.index,
                            total = progress.total,
                            estimatedRemainingMs = progress.estimatedRemainingMs,
                            foundSoFar = allChanges.size,
                        )
                    }
                    is MetadataEnrichmentScanner.ScanProgress.FoundChange -> {
                        allChanges.add(progress.change)
                    }
                    is MetadataEnrichmentScanner.ScanProgress.Done -> {
                        val highConfidence = allChanges.indices
                            .filter { allChanges[it].confidence == MetadataEnrichmentScanner.Confidence.HIGH }
                            .toSet()
                        _checkedIndices.value = highConfidence
                        _uiState.value = EnrichmentUiState.Reviewing(allChanges.toList())
                    }
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _uiState.value = EnrichmentUiState.Idle
    }

    fun toggleChange(index: Int) {
        _checkedIndices.value = if (index in _checkedIndices.value) {
            _checkedIndices.value - index
        } else {
            _checkedIndices.value + index
        }
    }

    fun selectAllHighConfidence() {
        _checkedIndices.value = allChanges.indices
            .filter { allChanges[it].confidence == MetadataEnrichmentScanner.Confidence.HIGH }
            .toSet()
    }

    fun applySelected() {
        val toApply = _checkedIndices.value.mapNotNull { allChanges.getOrNull(it) }
        val skipped = allChanges.size - toApply.size
        _uiState.value = EnrichmentUiState.Applying
        viewModelScope.launch {
            var updated = 0
            var failed = 0
            for (change in toApply) {
                val currentTags = tagEditor.readTags(change.song)
                val newTags = when (change.field) {
                    MetadataEnrichmentScanner.Field.YEAR -> currentTags.copy(year = change.newValue)
                    MetadataEnrichmentScanner.Field.GENRE -> currentTags.copy(genre = change.newValue)
                }
                if (tagEditor.writeTags(change.song, newTags)) updated++ else failed++
            }
            _uiState.value = EnrichmentUiState.Done(updated, failed, skipped)
        }
    }

    fun reset() {
        scanJob?.cancel()
        allChanges = mutableListOf()
        _checkedIndices.value = emptySet()
        _uiState.value = EnrichmentUiState.Idle
    }
}
