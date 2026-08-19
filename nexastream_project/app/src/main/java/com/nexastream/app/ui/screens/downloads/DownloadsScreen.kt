package com.nexastream.app.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nexastream.app.models.Download

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onPlayClick: (String) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads", color = Color.White) },
                actions = {
                    if (downloads.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllDownloads() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No downloads yet", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(downloads, key = { it.id }) { download ->
                    DownloadItem(
                        download = download,
                        onPlay = { onPlayClick(download.id) },
                        onPause = { viewModel.pauseDownload(download.id) },
                        onResume = { viewModel.resumeDownload(download.id) },
                        onRetry = { viewModel.retryDownload(download.id) },
                        onDelete = { viewModel.deleteDownload(download.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadItem(
    download: Download,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable(enabled = download.status == Download.Status.COMPLETED) { onPlay() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = download.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(100.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.DarkGray)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = download.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            
            if (download.status == Download.Status.DOWNLOADING) {
                val hasKnownProgress = download.progress > 0
                if (hasKnownProgress) {
                    LinearProgressIndicator(
                        progress = { download.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color.Red,
                        trackColor = Color.DarkGray
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color.Red,
                        trackColor = Color.DarkGray
                    )
                }
                Text(
                    text = if (hasKnownProgress) {
                        "Downloading... ${download.progress}%"
                    } else {
                        "Downloading..."
                    },
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                DownloadMetadata(download)
            } else {
                Text(
                    text = statusLabel(download),
                    color = statusColor(download),
                    fontSize = 12.sp
                )
                if (download.status == Download.Status.PAUSED || download.status == Download.Status.COMPLETED) {
                    DownloadMetadata(download)
                }
                if (download.status == Download.Status.FAILED && !download.errorMessage.isNullOrEmpty()) {
                    Text(
                        text = download.errorMessage,
                        color = Color.Red.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 2
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            when (download.status) {
                Download.Status.DOWNLOADING -> {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color.White)
                    }
                }
                Download.Status.PAUSED -> {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color.White)
                    }
                }
                Download.Status.FAILED -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White)
                    }
                }
                Download.Status.COMPLETED -> {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                    }
                }
                Download.Status.QUEUED -> Unit
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
            }
        }
    }
}

@Composable
private fun DownloadMetadata(download: Download) {
    val parts = listOfNotNull(
        download.quality?.takeIf { it.isNotBlank() },
        storageLabel(download),
        formatSizeProgress(download).takeIf { it.isNotBlank() },
        formatSpeed(download.downloadSpeed).takeIf { download.status == Download.Status.DOWNLOADING && download.downloadSpeed > 0L },
        formatEta(download.etaSeconds).takeIf { download.status == Download.Status.DOWNLOADING && download.etaSeconds != null }
    )

    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" - "),
            color = Color.Gray,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

private fun statusLabel(download: Download): String {
    return when (download.status) {
        Download.Status.FAILED -> "Failed"
        Download.Status.PAUSED -> "Paused"
        Download.Status.COMPLETED -> "Ready to watch"
        Download.Status.QUEUED -> "Queued"
        Download.Status.DOWNLOADING -> "Downloading"
    }
}

private fun statusColor(download: Download): Color {
    return when (download.status) {
        Download.Status.COMPLETED -> Color.Green
        Download.Status.FAILED -> Color.Red
        Download.Status.PAUSED -> Color(0xFFFFC107)
        else -> Color.Gray
    }
}

private fun formatSizeProgress(download: Download): String {
    return when {
        download.downloadedSize > 0L && download.totalSize > 0L -> {
            "${formatBytes(download.downloadedSize)} of ${formatBytes(download.totalSize)}"
        }
        download.downloadedSize > 0L -> "${formatBytes(download.downloadedSize)} cached"
        download.totalSize > 0L -> formatBytes(download.totalSize)
        else -> ""
    }
}

private fun storageLabel(download: Download): String {
    return when (download.storageType) {
        Download.StorageType.DIRECT_FILE_CACHE -> "Offline video cache"
        Download.StorageType.HLS_CACHE -> "Offline HLS cache"
        Download.StorageType.DASH_CACHE -> "Offline DASH cache"
        Download.StorageType.MEDIA_CACHE -> "Offline media cache"
    }
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return "${formatBytes(bytesPerSecond)}/s"
}

private fun formatEta(seconds: Long?): String? {
    if (seconds == null || seconds <= 0L) return null

    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60

    return when {
        hours > 0L -> "${hours}h ${minutes}m left"
        minutes > 0L -> "${minutes}m ${remainingSeconds}s left"
        else -> "${remainingSeconds}s left"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return if (value >= 10 || unitIndex == 0) {
        "%.0f %s".format(value, units[unitIndex])
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}
