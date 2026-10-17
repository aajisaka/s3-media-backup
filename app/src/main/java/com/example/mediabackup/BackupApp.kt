package com.example.mediabackup

import android.app.Application
import com.example.mediabackup.data.BackupRepository
import com.example.mediabackup.data.SettingsRepository

/**
 * Application scope. Holds the two singletons that both the UI and the background
 * worker need: the settings store and the backup engine (which owns live progress).
 */
class BackupApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var repository: BackupRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        repository = BackupRepository(this, settingsRepository)
    }
}
