package com.nexastream.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nexastream.app.models.Show
import com.nexastream.app.ui.components.HeroComponent
import com.nexastream.app.ui.components.NexastreamTopBar
import com.nexastream.app.ui.components.PosterRow

@Composable
fun HomeScreen(
    onMovieClick: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberLazyListState()
    
    // Calculate TopBar alpha based on scroll
    val topBarAlpha by remember {
        derivedStateOf {
            if (scrollState.firstVisibleItemIndex > 0) 1f
            else {
                val firstVisibleItemOffset = scrollState.firstVisibleItemScrollOffset
                val threshold = 300f
                (firstVisibleItemOffset / threshold).coerceIn(0f, 1f)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Red
            )
        } else if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = scrollState
            ) {
                item {
                    HeroComponent(
                        movie = uiState.heroMovie,
                        onPlayClick = { uiState.heroMovie?.let { onMovieClick(it.id) } },
                        onInfoClick = { uiState.heroMovie?.let { onMovieClick(it.id) } }
                    )
                }
                
                if (uiState.continueWatching.isNotEmpty()) {
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                    item {
                        PosterRow(
                            title = "Continue Watching",
                            shows = uiState.continueWatching,
                            onShowClick = onMovieClick
                        )
                    }
                }

                uiState.categories.forEach { category ->
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                    item {
                        PosterRow(
                            title = category.name,
                            shows = category.list.filterIsInstance<Show>(),
                            onShowClick = onMovieClick
                        )
                    }
                }
                
                item { Spacer(modifier = Modifier.height(80.dp)) } // Extra space for bottom bar
            }

            // TopBar overlay
            NexastreamTopBar(alpha = topBarAlpha)
        }
    }
}
