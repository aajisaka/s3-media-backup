package com.example.mediabackup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediabackup.data.Permissions
import com.example.mediabackup.ui.BackupViewModel
import com.example.mediabackup.ui.HomeScreen
import com.example.mediabackup.ui.LibraryScreen
import com.example.mediabackup.ui.SettingsScreen
import com.example.mediabackup.ui.theme.MediaBackupTheme

private enum class Screen { HOME, SETTINGS, LIBRARY }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MediaBackupTheme {
                BackupRoot()
            }
        }
    }
}

@Composable
private fun BackupRoot() {
    val context = LocalContext.current
    val viewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()

    // Bumped whenever permission state may have changed (user granted something, or
    // they changed which media types to back up, which changes what we must hold).
    var permissionRevision by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
    }

    val mediaGranted = remember(settings, permissionRevision) {
        Permissions.hasMediaAccess(context, settings)
    }
    val notificationsGranted = remember(permissionRevision) {
        Permissions.hasNotificationAccess(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionRevision++
                // Also refreshes the library, so returning from the system player lands
                // on badges that match whatever happened while we were away.
                viewModel.refreshFromDevice()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var screen by remember { mutableStateOf(Screen.HOME) }

    when (screen) {
        Screen.HOME -> HomeScreen(
            status = status,
            settings = settings,
            mediaPermissionGranted = mediaGranted,
            notificationPermissionGranted = notificationsGranted,
            onRequestPermissions = {
                permissionLauncher.launch(
                    Permissions.requiredPermissions(settings) +
                        listOfNotNull(Permissions.notificationPermission()),
                )
            },
            onBackupNow = viewModel::backupNow,
            onOpenLibrary = { screen = Screen.LIBRARY },
            onOpenSettings = { screen = Screen.SETTINGS },
        )

        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onSave = {
                viewModel.updateSettings { _ -> it }
                screen = Screen.HOME
            },
            onBack = { screen = Screen.HOME },
        )

        Screen.LIBRARY -> LibraryScreen(
            entries = library,
            mediaPermissionGranted = mediaGranted,
            // Media permissions only: the library has nothing to say about notifications.
            onRequestPermissions = {
                permissionLauncher.launch(Permissions.requiredPermissions(settings))
            },
            onBack = { screen = Screen.HOME },
        )
    }
}
