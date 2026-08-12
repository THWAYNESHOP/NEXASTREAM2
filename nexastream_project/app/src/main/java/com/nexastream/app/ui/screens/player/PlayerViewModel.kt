package com.nexastream.app.ui.screens.player

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder

data class PlayerUiState(
    val isLoading: Boolean = false,
    val video: Video? = null,
    val movie: Movie? = null,
    val tvShow: TvShow? = null,
    val currentEpisode: Episode? = null,
    val servers: List<Video.Server> = emptyList(),
    val currentServer: Video.Server? = null,
    val error: String? = null
)

class PlayerViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val id: String = URLDecoder.decode(checkNotNull(savedStateHandle["id"]), "UTF-8")
    
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    init {
        loadVideo()
    }

    fun retry() {
        loadVideo()
    }

    fun selectServer(server: Video.Server) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, currentServer = server) }
            try {
                val video = UserPreferences.currentProvider!!.getVideo(server)
                if (video.source.isEmpty()) throw Exception("Empty source from ${server.name}")
                _uiState.update { it.copy(video = video, isLoading = false) }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Manual server switch failed: ${e.message}")
                _uiState.update { it.copy(error = "Server ${server.name} failed: ${e.message}", isLoading = false) }
            }
        }
    }

    fun onPlayerError() {
        // Automatically try next server on error
        val currentServers = _uiState.value.servers
        val currentServer = _uiState.value.currentServer
        val currentIndex = currentServers.indexOf(currentServer)
        
        if (currentIndex != -1 && currentIndex < currentServers.size - 1) {
            val nextServer = currentServers[currentIndex + 1]
            Log.i("PlayerViewModel", "Playback error, auto-rotating to: ${nextServer.name}")
            selectServer(nextServer)
        } else {
            _uiState.update { it.copy(error = "All servers failed. Please try again later.", isLoading = false) }
        }
    }

    private fun loadVideo() {
        val provider = UserPreferences.currentProvider ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                var videoType: Video.Type? = null
                var movie: Movie? = null
                var tvShow: TvShow? = null
                var currentEpisode: Episode? = null

                // Determine type based on ID content
                if (id.contains("/movie/")) {
                    movie = provider.getMovie(id)
                    videoType = Video.Type.Movie(
                        id = movie.id,
                        title = movie.title,
                        releaseDate = movie.released?.format("yyyy-MM-dd") ?: "",
                        poster = movie.poster ?: "",
                        imdbId = movie.imdbId
                    )
                } else if (id.contains("/series/") || id.contains("/tv/") || id.contains("episode_id=")) {
                    if (id.contains("episode_id=")) {
                        videoType = Video.Type.Episode(
                            id = id,
                            number = 1,
                            title = "",
                            poster = null,
                            overview = null,
                            tvShow = Video.Type.Episode.TvShow(id="", title="", poster=null, banner=null, releaseDate=null, imdbId=null),
                            season = Video.Type.Episode.Season(number=1, title=null)
                        )
                    } else {
                        tvShow = provider.getTvShow(id)
                        val firstEpisode = tvShow.seasons.firstOrNull()?.episodes?.firstOrNull()
                        videoType = Video.Type.Episode(
                            id = firstEpisode?.id ?: id,
                            number = firstEpisode?.number ?: 1,
                            title = firstEpisode?.title,
                            poster = firstEpisode?.poster,
                            overview = firstEpisode?.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = tvShow.id,
                                title = tvShow.title,
                                poster = tvShow.poster,
                                banner = tvShow.banner,
                                releaseDate = tvShow.released?.format("yyyy-MM-dd"),
                                imdbId = tvShow.imdbId
                            ),
                            season = Video.Type.Episode.Season(
                                number = tvShow.seasons.firstOrNull()?.number ?: 1,
                                title = tvShow.seasons.firstOrNull()?.title
                            )
                        )
                    }
                } else {
                    runCatching {
                        movie = provider.getMovie(id)
                        videoType = Video.Type.Movie(
                            id = movie!!.id,
                            title = movie!!.title,
                            releaseDate = movie!!.released?.format("yyyy-MM-dd") ?: "",
                            poster = movie!!.poster ?: "",
                            imdbId = movie!!.imdbId
                        )
                    }.onFailure {
                        tvShow = provider.getTvShow(id)
                        videoType = Video.Type.Episode(
                            id = id, number=1, title="", poster=null, overview=null,
                            tvShow=Video.Type.Episode.TvShow(id="", title="", poster=null, banner=null, releaseDate=null, imdbId=null),
                            season=Video.Type.Episode.Season(number=1, title=null)
                        )
                    }
                }

                Log.d("PlayerViewModel", "Fetching servers for ID: $id with type: $videoType")
                val servers = provider.getServers(id, videoType!!)
                if (servers.isEmpty()) throw Exception("No servers found for this content")

                _uiState.update { it.copy(servers = servers, movie = movie, tvShow = tvShow) }
                
                // Start with first server
                selectServer(servers.first())

            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Initial load failed: ${e.message}", e)
                _uiState.update { 
                    it.copy(
                        error = e.message ?: "Failed to load movie servers",
                        isLoading = false
                    )
                }
            }
        }
    }
}
