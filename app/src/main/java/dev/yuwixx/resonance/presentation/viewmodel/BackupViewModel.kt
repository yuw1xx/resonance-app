package dev.yuwixx.resonance.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.yuwixx.resonance.data.service.BackupManager
import dev.yuwixx.resonance.data.service.RestoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupUiState {
    data object Idle : BackupUiState()
    data object Working : BackupUiState()
    data class ExportSuccess(val message: String) : BackupUiState()
    data class ImportSuccess(val message: String) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun exportBackup(uri: Uri) {
        _state.value = BackupUiState.Working
        viewModelScope.launch {
            val success = backupManager.createBackup(uri)
            _state.value = if (success) BackupUiState.ExportSuccess("Backup exported successfully")
                           else BackupUiState.Error("Failed to export backup")
        }
    }

    fun importBackup(uri: Uri) {
        _state.value = BackupUiState.Working
        viewModelScope.launch {
            _state.value = when (val result = backupManager.restoreBackup(uri)) {
                is RestoreResult.Success ->
                    BackupUiState.ImportSuccess(
                        "Restored ${result.likedRestored} liked songs and ${result.playlistsRestored} playlists"
                    )
                is RestoreResult.Error -> BackupUiState.Error(result.message)
            }
        }
    }

    fun dismiss() { _state.value = BackupUiState.Idle }
}
