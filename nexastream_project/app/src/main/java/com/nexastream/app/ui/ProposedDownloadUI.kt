package com.nexastream.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProposedDownloadUI() {
    var selectedResolution by remember { mutableStateOf("1080p") }
    val resolutions = listOf("1080p", "720p", "480p", "360p")

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

            // Resolution Selector
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                items(resolutions) { res ->
                    val isSelected = res == selectedResolution
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

            Spacer(modifier = Modifier.height(12.dp))

            // Server List
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ServerItemMockup(
                    name = "VixSrc",
                    details = "Segmented Stream • ~4.2 Mbps",
                    icon = Icons.Default.PlayCircle,
                    isRecommended = true,
                    isExtracting = false
                )
                ServerItemMockup(
                    name = "VidLink",
                    details = "Direct File (MP4) • 1.8 GB",
                    icon = Icons.Default.Download,
                    isRecommended = false,
                    isExtracting = false
                )
                ServerItemMockup(
                    name = "2Embed",
                    details = "Fetching link details...",
                    icon = Icons.Default.SlowMotionVideo,
                    isRecommended = false,
                    isExtracting = true
                )
            }
        }
    }
}

@Composable
fun ServerItemMockup(
    name: String,
    details: String,
    icon: ImageVector,
    isRecommended: Boolean,
    isExtracting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
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
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFE50914)
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isRecommended) Color(0xFFE50914) else Color.LightGray,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = Color.White,
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
                Text(
                    text = details,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Preview
@Composable
fun PreviewProposedDownloadUI() {
    ProposedDownloadUI()
}
