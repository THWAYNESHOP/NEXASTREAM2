package com.nexastream.app.ui.screens.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.nexastream.app.R
import com.nexastream.app.models.*
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.providers.Provider
import com.nexastream.app.ui.components.MoviePoster
import com.nexastream.app.utils.DownloadQualityFormatter
import com.nexastream.app.utils.format

@Composable
fun DetailScreen(
    onPlayClick: (String) -> Unit,
    onShowClick: (String) -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDownloadDialog by remember { mutableStateOf(false) }
    var selectedVideoType by remember { mutableStateOf<Video.Type?>(null) }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(id = R.string.detail_download_quality_title)) },
            text = {
                Column {
                    LaunchedEffect(selectedVideoType) {
                        selectedVideoType?.let { type ->
                            val downloadId = when (type) {
                                is Video.Type.Movie -> type.id
                                is Video.Type.Episode -> type.id
                            }
                            viewModel.loadServers(downloadId, type)
                        }
                    }

                    if (uiState.servers.isEmpty()) {
                        CircularProgressIndicator(color = Color.Red)
                    } else {
                        uiState.servers.forEach { server ->
                            val details = DownloadQualityFormatter.details(server)
                            ListItem(
                                headlineContent = { Text(DownloadQualityFormatter.title(server)) },
                                supportingContent = {
                                    if (details.isNotBlank()) {
                                        Text(details)
                                    }
                                },
                                modifier = Modifier.clickable {
                                    selectedVideoType?.let { viewModel.startDownload(server, it) }
                                    showDownloadDialog = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text(stringResource(id = R.string.option_cancel)) }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        } else if (uiState.error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = stringResource(id = R.string.detail_error_title), color = Color.Red, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = uiState.error!!, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = { viewModel.retry() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text(stringResource(id = R.string.loading_error_retry))
                }
            }
        } else if (uiState.show != null) {
            val show = uiState.show!!
            
            var selectedSeason by remember { 
                mutableStateOf(if (show is TvShow) show.seasons.firstOrNull() else null) 
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    AsyncImage(
                        model = show.banner ?: show.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    )
                }

                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = show.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val released = when(show) {
                                is Movie -> show.released
                                is TvShow -> show.released
                                else -> null
                            }
                            Text(text = released?.format("yyyy") ?: "", color = Color.Gray, fontSize = 14.sp)
                            
                            val rating = when(show) {
                                is Movie -> show.rating
                                is TvShow -> show.rating
                                else -> null
                            }
                            if (rating != null) {
                                Text(text = "$rating Rating", color = Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            val quality = when(show) {
                                is Movie -> show.quality
                                is TvShow -> show.quality
                                else -> null
                            }
                            if (quality != null) {
                                Box(modifier = Modifier
                                    .border(1.dp, Color.Gray, RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)) {
                                    Text(text = quality, color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            val downloadsSupported = Provider.supportsDownloads(UserPreferences.currentProvider)

                            Button(
                                onClick = { onPlayClick(show.id) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(id = R.string.detail_play), color = Color.Black, fontWeight = FontWeight.Bold)
                            }

                            if (show is Movie && downloadsSupported) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        selectedVideoType = Video.Type.Movie(show.id, show.title, show.released?.format("yyyy-MM-dd") ?: "", show.poster ?: "", show.imdbId)
                                        showDownloadDialog = true
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(id = R.string.movie_download), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            IconButton(
                                onClick = { viewModel.toggleFavorite() },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.DarkGray.copy(alpha = 0.5f))
                            ) {
                                Icon(
                                    imageVector = if (show.isFavorite) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = "My List",
                                    tint = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = when(show) {
                                is Movie -> show.overview ?: ""
                                is TvShow -> show.overview ?: ""
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            lineHeight = 20.sp
                        )
                    }
                }

                // Cast
                val cast = when(show) {
                    is Movie -> show.cast
                    is TvShow -> show.cast
                    else -> emptyList()
                }
                if (cast.isNotEmpty()) {
                    item {
                        CastRow(cast = cast)
                    }
                }

                // TV Show Sections
                if (show is TvShow) {
                    item {
                        SeasonSelector(
                            seasons = show.seasons,
                            selectedSeason = selectedSeason,
                            onSeasonSelected = { selectedSeason = it }
                        )
                    }
                    
                    selectedSeason?.episodes?.let { episodes ->
                        items(episodes) { episode ->
                            EpisodeItem(
                                episode = episode,
                                onClick = { onPlayClick(episode.id) },
                                onDownloadClick = {
                                    selectedVideoType = Video.Type.Episode(
                                        id = episode.id,
                                        number = episode.number,
                                        title = episode.title,
                                        poster = episode.poster,
                                        overview = episode.overview,
                                        tvShow = Video.Type.Episode.TvShow(
                                            id = show.id,
                                            title = show.title,
                                            poster = show.poster,
                                            banner = show.banner,
                                            releaseDate = show.released?.format("yyyy-MM-dd"),
                                            imdbId = show.imdbId,
                                        ),
                                        season = Video.Type.Episode.Season(
                                            number = selectedSeason!!.number,
                                            title = selectedSeason!!.title,
                                        ),
                                    )
                                    showDownloadDialog = true
                                }
                            )
                        }
                    }
                }

                // Recommendations
                val recommendations = when(show) {
                    is Movie -> show.recommendations
                    is TvShow -> show.recommendations
                    else -> emptyList()
                }
                if (recommendations.isNotEmpty()) {
                    item {
                        RecommendationsRow(
                            recommendations = recommendations,
                            onShowClick = onShowClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CastRow(cast: List<People>) {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            text = stringResource(id = R.string.tv_show_cast),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(cast) { person ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(80.dp)
                ) {
                    AsyncImage(
                        model = person.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color.DarkGray)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = person.name,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun SeasonSelector(
    seasons: List<Season>,
    selectedSeason: Season?,
    onSeasonSelected: (Season) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.padding(16.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            Text(text = selectedSeason?.title ?: stringResource(id = R.string.detail_select_season))
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.DarkGray)
        ) {
            seasons.forEach { season ->
                DropdownMenuItem(
                    text = { Text(season.title ?: "Season ${season.number}", color = Color.White) },
                    onClick = {
                        onSeasonSelected(season)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun EpisodeItem(
    episode: Episode,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val downloadsSupported = Provider.supportsDownloads(UserPreferences.currentProvider)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(120.dp).height(70.dp)) {
            AsyncImage(
                model = episode.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.DarkGray)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.number}. ${episode.title ?: "Episode ${episode.number}"}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = episode.overview ?: "",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (downloadsSupported) {
            IconButton(onClick = onDownloadClick) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.Gray)
            }
        }
    }
}

@Composable
fun RecommendationsRow(
    recommendations: List<Show>,
    onShowClick: (String) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 16.dp)) {
        Text(
            text = stringResource(id = R.string.detail_more_like_this),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(recommendations) { show ->
                val posterUrl = when(show) {
                    is Movie -> show.poster
                    is TvShow -> show.poster
                    else -> null
                }
                MoviePoster(
                    posterUrl = posterUrl,
                    onClick = { onShowClick(show.id) }
                )
            }
        }
    }
}
