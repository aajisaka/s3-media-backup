package com.example.mediabackup.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.mediabackup.BackupApp
import com.example.mediabackup.data.BackupSettings
import com.example.mediabackup.data.BackupStatus
import com.example.mediabackup.data.LibraryEntry
import com.example.mediabackup.work.BackupScheduler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Bridges the Compose UI to the application-scoped singletons. The [BackupApp] reference
 * doubles as the Context that WorkManager needs.
 */
class BackupViewModel(private val app: BackupApp) : ViewModel() {

    val settings: StateFlow<BackupSettings> = app.settingsRepository.settings
    val status: StateFlow<BackupStatus> = app.repository.status
    val library: StateFlow<List<LibraryEntry>> = app.repository.library

    fun updateSettings(transform: (BackupSettings) -> BackupSettings) {
        app.settingsRepository.update(transform)
        // Re-register the periodic job whenever a scheduling knob changes.
        BackupScheduler.applySchedule(app, app.settingsRepository.current())
    }

    fun backupNow() {
        BackupScheduler.runNow(app)
    }

    fun refreshFromDevice() {
        viewModelScope.launch { app.repository.refreshFromDevice() }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BackupApp
                BackupViewModel(app)
            }
        }
    }
}
