package com.example.mediabackup.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.mediabackup.data.BackupSettings

/**
 * Plain settings form with an explicit Save, so the periodic WorkManager job is only
 * re-registered once instead of on every keystroke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: BackupSettings,
    onSave: (BackupSettings) -> Unit,
    onBack: () -> Unit,
) {
    var region by rememberSaveable { mutableStateOf(settings.region) }
    var identityPoolId by rememberSaveable { mutableStateOf(settings.identityPoolId) }
    var bucket by rememberSaveable { mutableStateOf(settings.bucket) }
    var keyPrefix by rememberSaveable { mutableStateOf(settings.keyPrefix) }
    var intervalHours by rememberSaveable { mutableStateOf(settings.autoBackupIntervalHours.toString()) }
    var backupImages by rememberSaveable { mutableStateOf(settings.backupImages) }
    var backupVideos by rememberSaveable { mutableStateOf(settings.backupVideos) }
    var autoBackup by rememberSaveable { mutableStateOf(settings.autoBackupEnabled) }
    var requireUnmetered by rememberSaveable { mutableStateOf(settings.requireUnmetered) }
    var requireCharging by rememberSaveable { mutableStateOf(settings.requireCharging) }

    val regionError = region.isNotBlank() && !REGION_REGEX.matches(region.trim())
    val intervalValid = intervalHours.toIntOrNull()?.let { it in 1..8760 } ?: false
    val canSave = region.trim().isNotBlank() && !regionError && intervalValid

    // Same reason as the library: the system back button should step back to the home
    // screen, not drop out of the app.
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SectionTitle("AWS")

            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("Region") },
                supportingText = {
                    Text(if (regionError) "e.g. us-east-1, ap-northeast-1" else "Bucket region, e.g. us-east-1")
                },
                isError = regionError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = identityPoolId,
                onValueChange = { identityPoolId = it },
                label = { Text("Cognito Identity Pool ID") },
                supportingText = { Text("e.g. us-east-1:00000000-0000-0000-0000-000000000000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = bucket,
                onValueChange = { bucket = it },
                label = { Text("S3 bucket name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = keyPrefix,
                onValueChange = { keyPrefix = it },
                label = { Text("Key prefix") },
                supportingText = { Text("Objects land at <prefix>/photos/… and <prefix>/videos/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            SectionTitle("What to back up")
            ToggleRow("Photos", "Images from the device gallery", backupImages) { backupImages = it }
            ToggleRow("Videos", "Movies and recorded clips", backupVideos) { backupVideos = it }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            SectionTitle("Schedule")
            ToggleRow("Automatic backup", "Run on a repeating schedule", autoBackup) { autoBackup = it }

            if (autoBackup) {
                OutlinedTextField(
                    value = intervalHours,
                    onValueChange = { intervalHours = it.filter(Char::isDigit).take(4) },
                    label = { Text("Interval (hours)") },
                    supportingText = {
                        Text(if (intervalValid) "Android may delay runs to save battery" else "Enter 1 to 8760")
                    },
                    isError = !intervalValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow("Wi-Fi only", "Skip backups on metered connections", requireUnmetered) {
                    requireUnmetered = it
                }
                ToggleRow("Only while charging", "Wait for external power", requireCharging) {
                    requireCharging = it
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            Button(
                onClick = {
                    onSave(
                        BackupSettings(
                            region = region.trim(),
                            identityPoolId = identityPoolId.trim(),
                            bucket = bucket.trim(),
                            keyPrefix = keyPrefix.trim().ifEmpty { "media" },
                            backupImages = backupImages,
                            backupVideos = backupVideos,
                            autoBackupEnabled = autoBackup,
                            autoBackupIntervalHours = intervalHours.toIntOrNull() ?: 24,
                            requireUnmetered = requireUnmetered,
                            requireCharging = requireCharging,
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text("Save")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val REGION_REGEX = Regex("^[a-z]{2}(-[a-z]+)+-\\d$")
