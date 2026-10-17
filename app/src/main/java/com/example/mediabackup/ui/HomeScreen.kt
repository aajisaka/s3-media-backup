package com.example.mediabackup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediabackup.data.BackupSettings
import com.example.mediabackup.data.BackupStatus
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    status: BackupStatus,
    settings: BackupSettings,
    mediaPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onBackupNow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("S3 Media Backup") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!settings.isConfigured) {
                ActionCard(
                    icon = { Icon(Icons.Outlined.CloudUpload, null) },
                    title = "Connect your S3 bucket",
                    body = "Add your AWS region, Cognito Identity Pool ID and bucket name to start backing up.",
                    actionLabel = "Open settings",
                    onAction = onOpenSettings,
                )
            }

            if (!mediaPermissionGranted) {
                ActionCard(
                    icon = { Icon(Icons.Outlined.Folder, null) },
                    title = "Media access needed",
                    body = "Grant access to photos and videos so they can be uploaded.",
                    actionLabel = "Grant access",
                    onAction = onRequestPermissions,
                )
            } else if (!notificationPermissionGranted) {
                ActionCard(
                    icon = { Icon(Icons.Outlined.ErrorOutline, null) },
                    title = "Progress notifications are off",
                    body = "Long backups run in the background. Allow notifications to see progress.",
                    actionLabel = "Allow notifications",
                    onAction = onRequestPermissions,
                )
            }

            StatusCard(status, settings)

            Button(
                onClick = onBackupNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = settings.isConfigured &&
                    mediaPermissionGranted &&
                    !status.isRunning &&
                    (settings.backupImages || settings.backupVideos),
            ) {
                Icon(Icons.Outlined.CloudUpload, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        status.isRunning -> "Backup in progress…"
                        !settings.backupImages && !settings.backupVideos -> "Nothing selected to back up"
                        else -> "Back up now"
                    },
                )
            }

            if (status.lastErrors.isNotEmpty()) {
                ErrorsCard(status.lastErrors)
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(status: BackupStatus, settings: BackupSettings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (status.isRunning) status.phase.ifEmpty { "Working" } else "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (status.isRunning) {
                LinearProgressIndicator(
                    progress = { status.overallFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = if (status.totalCount > 0) {
                        "${status.completedCount} of ${status.totalCount} · " +
                            "${formatBytes(status.uploadedBytes)} of ${formatBytes(status.totalBytes)}"
                    } else {
                        status.phase
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (status.currentFileName.isNotEmpty()) {
                    Text(
                        text = status.currentFileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "Backed up",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Only show the media types currently being backed up: with photos
                    // disabled the scan never looks at them, so a "0 photos" tile would
                    // be reporting on something the app is no longer tracking.
                    if (settings.backupImages) {
                        Stat(Icons.Outlined.Image, "${status.photosBackedUp}", "photos")
                    }
                    if (settings.backupVideos) {
                        Stat(Icons.Outlined.Movie, "${status.videosBackedUp}", "videos")
                    }
                    Stat(Icons.Outlined.CheckCircle, "${status.backedUpCount}", "total")
                }

                HorizontalThinRule()

                Text(
                    text = "${status.pendingCount} pending",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = if (status.lastRunAtMillis > 0) {
                        "Last backup: ${formatWhen(status.lastRunAtMillis)}" +
                            if (status.lastResultMessage.isNotEmpty()) " · ${status.lastResultMessage}" else ""
                    } else {
                        "No backups yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleSmall)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun HorizontalThinRule() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun ActionCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ErrorsCard(errors: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Some files could not be uploaded",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            errors.forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "These will be retried on the next backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

/**
 * Locale-aware date and time. Using a fixed pattern with a locale-dependent month
 * name mixes conventions (e.g. "10月 18, 2026" on a Japanese device), so let the
 * platform pick the ordering for the device locale.
 */
private val dateFormat: java.text.DateFormat =
    java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
        Locale.getDefault(),
    )

internal fun formatWhen(millis: Long): String = dateFormat.format(Date(millis))
