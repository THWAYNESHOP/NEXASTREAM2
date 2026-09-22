package com.nexastream.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nexastream.app.models.Video
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.utils.DownloadQualityFormatter
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

@androidx.media3.common.util.UnstableApi
@AndroidEntryPoint
class DownloadQualityBottomSheet : BottomSheetDialogFragment() {

    private var servers: List<Video.Server> = emptyList()
    private var mediaId: String = ""
    private var mediaTitle: String = ""
    private var mediaPoster: String? = null
    private var videoType: Video.Type? = null

    private val downloadManager: DownloadManager by lazy {
        val entryPoint = EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            DownloadManagerEntryPoint::class.java
        )
        entryPoint.downloadManager()
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface DownloadManagerEntryPoint {
        fun downloadManager(): DownloadManager
    }

    companion object {
        private const val ARG_SERVERS = "arg_servers"
        private const val ARG_MEDIA_ID = "arg_media_id"
        private const val ARG_MEDIA_TITLE = "arg_media_title"
        private const val ARG_MEDIA_POSTER = "arg_media_poster"
        private const val ARG_VIDEO_TYPE = "arg_video_type"

        fun newInstance(
            servers: List<Video.Server>,
            mediaId: String,
            mediaTitle: String,
            mediaPoster: String?,
            videoType: Video.Type
        ): DownloadQualityBottomSheet {
            return DownloadQualityBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_SERVERS, ArrayList(servers))
                    putString(ARG_MEDIA_ID, mediaId)
                    putString(ARG_MEDIA_TITLE, mediaTitle)
                    putString(ARG_MEDIA_POSTER, mediaPoster)
                    putSerializable(ARG_VIDEO_TYPE, videoType)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            servers = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getSerializable(ARG_SERVERS, java.util.ArrayList::class.java) as? List<Video.Server>
                } else {
                    it.getSerializable(ARG_SERVERS) as? List<Video.Server>
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            mediaId = it.getString(ARG_MEDIA_ID, "")
            mediaTitle = it.getString(ARG_MEDIA_TITLE, "")
            mediaPoster = it.getString(ARG_MEDIA_POSTER)
            videoType = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getSerializable(ARG_VIDEO_TYPE, Video.Type::class.java)
                } else {
                    it.getSerializable(ARG_VIDEO_TYPE) as? Video.Type
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val provider = remember { UserPreferences.currentProvider }
                var updatedServers by remember { mutableStateOf(servers) }
                val extractionStatus = remember { mutableStateMapOf<String, String>() }

                LaunchedEffect(servers) {
                    if (provider != null) {
                        servers.forEach { server ->
                            if (server.video == null) {
                                launch(Dispatchers.IO) {
                                    extractionStatus[server.id] = "loading"
                                    try {
                                        val video = provider.getVideo(server)
                                        server.video = video
                                        updatedServers = updatedServers.map { if (it.id == server.id) it.copy().apply { this.video = video } else it }
                                        extractionStatus[server.id] = "done"
                                    } catch (e: Exception) {
                                        extractionStatus[server.id] = "failed"
                                        android.util.Log.e("DownloadBS", "Background fetch failed for ${server.name}: ${e.message}")
                                    }
                                }
                            } else {
                                extractionStatus[server.id] = "done"
                            }
                        }
                    }
                }

                var selectedResolution by remember { mutableStateOf("") }

                fun getSupportedResolutions(server: Video.Server): List<String> {
                    val label = DownloadQualityFormatter.title(server).substringBefore(" - ")
                    if (label != "Unknown" && label.endsWith("p")) {
                        return listOf(label)
                    }

                    val sourceUrl = server.video?.source.orEmpty()
                    if (sourceUrl.startsWith("data:application/vnd.apple.mpegurl;base64,")) {
                        try {
                            val base64Data = sourceUrl.substringAfter("base64,")
                            val decodedBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            val manifestContent = String(decodedBytes, Charsets.UTF_8)
                            val resolutions = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                                .findAll(manifestContent)
                                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                                .map { "${it}p" }
                                .toList()
                            if (resolutions.isNotEmpty()) {
                                return resolutions.distinct()
                            }
                        } catch (e: Exception) {}
                    }

                    val text = "${server.name} ${server.src} $sourceUrl".lowercase()
                    val numeric = Regex("""(?<!\d)(2160|1440|1080|720|576|540|480|360|240)p?(?!\d)""").find(text)?.groupValues?.getOrNull(1)
                    if (numeric != null) return listOf("${numeric}p")

                    if (text.contains("vixsrc") || text.contains("2embed") || text.contains("vidsrc") || text.contains("vidlink") || text.contains("vidflix")) {
                        return listOf("1080p", "720p", "480p", "360p")
                    }
                    return listOf("720p")
                }

                val resolutions = remember(updatedServers) {
                    updatedServers.flatMap { getSupportedResolutions(it) }.distinct().sortedByDescending {
                        it.substringBefore("p").toIntOrNull() ?: 0
                    }
                }

                val activeResolution = if (selectedResolution.isNotBlank() && selectedResolution in resolutions) {
                    selectedResolution
                } else {
                    resolutions.firstOrNull() ?: ""
                }

                Surface(
                    color = Color(0xFF141416),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 10.dp, bottom = 24.dp)
                    ) {
                        // Drag Handle
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.DarkGray, RoundedCornerShape(2.dp))
                                .align(Alignment.CenterHorizontally)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Select Download Quality",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (resolutions.isNotEmpty()) {
                            @OptIn(ExperimentalMaterial3Api::class)
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                items(resolutions) { res ->
                                    val isSelected = res == activeResolution
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedResolution = res },
                                        label = { Text(res, fontWeight = FontWeight.Bold) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = Color(0xFF222225),
                                            labelColor = Color.Gray,
                                            selectedContainerColor = Color(0xFFE50914),
                                            selectedLabelColor = Color.White
                                        ),
                                        border = null,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val filteredServers = remember(activeResolution, updatedServers) {
                            updatedServers.filter { activeResolution in getSupportedResolutions(it) }
                        }

                        fun handleServerClick(server: Video.Server) {
                            startExtractionAndDownload(server, activeResolution)
                            dismiss()
                        }

                        if (filteredServers.isEmpty() && servers.isNotEmpty()) {
                            Text(
                                text = "No options available for this quality",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 32.dp).align(Alignment.CenterHorizontally)
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 450.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(filteredServers) { server ->
                                val status = extractionStatus[server.id] ?: "loading"
                                val isDone = status == "done"
                                val isFailed = status == "failed"
                                
                                val rawDetails = DownloadQualityFormatter.details(server)
                                val details = if (isFailed) "Failed to extract link" 
                                             else if (!isDone) "Fetching link details..."
                                             else rawDetails

                                val isRecommended = isDone && (details.contains("Mbps") || details.contains("GB"))

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = isDone) {
                                            handleServerClick(server)
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF1E1E22),
                                        disabledContainerColor = Color(0xFF1E1E22).copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (isRecommended) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE50914).copy(alpha = 0.5f)) else null
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(Color(0xFF2A2A2E), RoundedCornerShape(8.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (status == "loading") {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Color(0xFFE50914)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (details.contains("MP4")) Icons.Default.Download else Icons.Default.PlayCircle,
                                                    contentDescription = null,
                                                    tint = if (isRecommended) Color(0xFFE50914) else if (isFailed) Color.DarkGray else Color.LightGray,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = DownloadQualityFormatter.title(server),
                                                    color = if (isFailed) Color.Gray else Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp
                                                )
                                                if (isRecommended) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    AssistChip(
                                                        onClick = {},
                                                        label = { Text("Recommended", fontSize = 10.sp) },
                                                        colors = AssistChipDefaults.assistChipColors(
                                                            containerColor = Color(0xFFE50914).copy(alpha = 0.1f),
                                                            labelColor = Color(0xFFE50914)
                                                        ),
                                                        border = null,
                                                        shape = RoundedCornerShape(4.dp),
                                                        modifier = Modifier.height(20.dp)
                                                    )
                                                }
                                            }
                                            if (details.isNotBlank()) {
                                                Text(
                                                    text = details,
                                                    color = if (isFailed) Color(0xFFE50914).copy(alpha = 0.7f) else Color.Gray,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startExtractionAndDownload(server: Video.Server, selectedQuality: String) {
        val provider = UserPreferences.currentProvider ?: return
        val appContext = requireContext().applicationContext
        val lifecycleScope = requireActivity().lifecycleScope
        
        Toast.makeText(appContext, "Starting extraction...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                val video = withContext(Dispatchers.IO) {
                    provider.getVideo(server)
                }
                
                downloadManager.startDownload(
                    id = mediaId,
                    title = mediaTitle,
                    poster = mediaPoster,
                    url = video.source,
                    quality = "$selectedQuality - ${DownloadQualityFormatter.title(server).substringAfter(" - ")}",
                    headers = video.headers,
                    mimeType = video.type
                )
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Download started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadBS", "Extraction failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to get video: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
