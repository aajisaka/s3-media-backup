package com.example.mediabackup.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediabackup.data.LibraryEntry
import kotlinx.coroutines.launch

/**
 * Everything the current settings put in scope, newest first, each tile marked with
 * whether it is already in S3. Tapping a tile hands the file to the system player.
 *
 * The tile shows the file on the *device*, not the object in the bucket, because that is
 * what can be played. A film uploaded and then deleted locally is therefore absent even
 * though it is still in S3 — the README covers that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    entries: List<LibraryEntry>,
    mediaPermissionGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Without this the system back button leaves the app from here, because only the
    // top-bar arrow is wired to the screen state.
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            !mediaPermissionGranted -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                ActionCard(
                    icon = Icons.Outlined.Folder,
                    title = "Media access needed",
                    body = "Grant access to photos and videos to browse and play them here.",
                    actionLabel = "Grant access",
                    onAction = onRequestPermissions,
                )
            }

            entries.isEmpty() -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                ActionCard(
                    icon = Icons.Outlined.Movie,
                    title = "Nothing to show",
                    body = "No photos or videos were found for the media types enabled in Settings.",
                )
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val videos = entries.count { it.item.isVideo }
                Text(
                    text = librarySummary(
                        videos = videos,
                        photos = entries.size - videos,
                        uploaded = entries.count { it.uploaded },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    // Keyed on the fingerprint so a tile keeps its identity across the
                    // reordering a backup causes, and so two rows can never collide.
                    items(entries, key = { it.item.fingerprint }) { entry ->
                        LibraryTile(
                            entry = entry,
                            onClick = {
                                if (!playMedia(context, entry.item)) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "No app on this device can open this file",
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTile(entry: LibraryEntry, onClick: () -> Unit) {
    val item = entry.item
    val resolver = LocalContext.current.contentResolver

    // Decoding happens off the main thread and is cancelled with the cell when it scrolls
    // away; the cache in Thumbnails.kt is what makes scrolling back cheap.
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.uri) {
        value = loadThumbnail(resolver, item.uri)
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumbnail
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = if (item.isVideo) Icons.Outlined.Movie else Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }

            UploadBadge(
                uploaded = entry.uploaded,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }

        Text(
            text = item.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        Text(
            text = formatBytes(item.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
        )
    }
}

/**
 * Whether this file is in S3, over the corner of the thumbnail.
 *
 * The two states differ in fill as well as icon, because a badge over an arbitrary frame
 * of video cannot rely on recognising a glyph — and this distinction is the reason the
 * screen exists.
 */
@Composable
private fun UploadBadge(uploaded: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (uploaded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(
                width = if (uploaded) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (uploaded) Icons.Outlined.Check else Icons.Outlined.CloudUpload,
            contentDescription = if (uploaded) "Backed up" else "Waiting to be backed up",
            tint = if (uploaded) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(15.dp),
        )
    }
}
