package com.nexastream.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow

@Composable
fun PosterRow(
    title: String,
    shows: List<Show>,
    onShowClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shows) { show ->
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
