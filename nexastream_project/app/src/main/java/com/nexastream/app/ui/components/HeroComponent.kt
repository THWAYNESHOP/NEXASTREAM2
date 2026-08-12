package com.nexastream.app.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.nexastream.app.models.Show
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow

@Composable
fun HeroComponent(
    movie: Show?,
    onPlayClick: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var backgroundColor by remember { mutableStateOf(Color.Black) }

    LaunchedEffect(movie?.banner) {
        movie?.banner?.let { url ->
            val loader = coil.ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()

            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                bitmap?.let {
                    Palette.from(it).generate { palette ->
                        palette?.darkMutedSwatch?.rgb?.let { rgb ->
                            backgroundColor = Color(rgb).copy(alpha = 0.8f)
                        } ?: palette?.dominantSwatch?.rgb?.let { rgb ->
                            backgroundColor = Color(rgb).copy(alpha = 0.5f)
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(600.dp)
            .background(Color.Black)
    ) {
        AsyncImage(
            model = movie?.banner ?: movie?.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Smoother Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.4f),
                        0.5f to Color.Transparent,
                        0.8f to Color.Black.copy(alpha = 0.5f),
                        1.0f to Color.Black
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            Text(
                text = movie?.title ?: "",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Genre Tags (Placeholder for now)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val genres = when (movie) {
                    is Movie -> movie.genres.take(3).map { it.name }
                    is TvShow -> movie.genres.take(3).map { it.name }
                    else -> listOf("Exciting", "Sci-Fi", "Drama")
                }.ifEmpty { listOf("Exciting", "Sci-Fi", "Drama") }

                genres.forEachIndexed { index, genre ->
                    Text(genre, color = Color.White, fontSize = 12.sp)
                    if (index < genres.size - 1) {
                        Box(modifier = Modifier.size(3.dp).background(Color.Gray, RoundedCornerShape(50)))
                    }
                }
            }
            
            // Show Quality/Rating
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                movie?.quality?.let {
                    Surface(
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.Gray),
                        shape = RoundedCornerShape(2.dp)
                    ) {
                        Text(
                            text = it,
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                movie?.rating?.let {
                    Text(
                        text = "⭐ %.1f".format(it),
                        color = Color.Yellow,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                    Text("My List", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = onPlayClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .height(40.dp)
                        .padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Color.White)
                    Text("Info", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}
