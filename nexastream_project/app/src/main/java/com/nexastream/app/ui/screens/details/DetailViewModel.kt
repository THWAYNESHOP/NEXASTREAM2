package com.nexastream.app.ui.screens.details

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.DownloadManager
import com.nexastream.app.models.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.DownloadQualityFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

data class DetailUiState(
    val isLoading: Boolean = false,
    val show: Show? = null,
    val servers: List<Video.Server> = emptyList(),
    val isDownloading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val id: String = URLDecoder.decode(checkNotNull(savedStateHandle["id"]), "UTF-8")
    
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    init {
        loadDetails()
    }

    fun retry() {
        loadDetails()
    }

    private fun loadDetails() {
        val provider = UserPreferences.currentProvider ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                // Try to get from DB first to check type and favorite status
                val dbMovie = withContext(Dispatchers.IO) { database.movieDao().getById(id) }
                val dbTvShow = withContext(Dispatchers.IO) { database.tvShowDao().getById(id) }

                val show = if (dbMovie != null) {
                    withContext(Dispatchers.IO) { provider.getMovie(id) }.apply {
                        isFavorite = dbMovie.isFavorite
                        favoritedAtMillis = dbMovie.favoritedAtMillis
                    }
                } else if (dbTvShow != null) {
                    withContext(Dispatchers.IO) { provider.getTvShow(id) }.apply {
                        isFavorite = dbTvShow.isFavorite
                        favoritedAtMillis = dbTvShow.favoritedAtMillis
                    }
                } else {
                    // Try to fetch as Movie first, then TvShow
                    withContext(Dispatchers.IO) {
                        if (id.contains("/movie/")) {
                            provider.getMovie(id)
                        } else if (id.contains("/series/") || id.contains("/tv/")) {
                            provider.getTvShow(id)
                        } else {
                            runCatching { provider.getMovie(id) }.getOrNull()
                                ?: provider.getTvShow(id)
                        }
                    }
                }

                _uiState.value = DetailUiState(show = show, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = DetailUiState(error = e.message ?: "Failed to load details", isLoading = false)
            }
        }
    }

    fun toggleFavorite() {
        val currentShow = _uiState.value.show ?: return
        val provider = UserPreferences.currentProvider ?: return
        
        viewModelScope.launch {
            val isFavorite = !currentShow.isFavorite
            currentShow.isFavorite = isFavorite
            
            if (currentShow is Movie) {
                if (isFavorite) {
                    currentShow.favoritedAtMillis = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        database.movieDao().insert(currentShow)
                        UserDataCache.addMovieToFavorites(context, provider, currentShow)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        database.movieDao().delete(currentShow)
                        UserDataCache.removeMovieFromFavorites(context, provider, currentShow.id)
                    }
                }
            } else if (currentShow is TvShow) {
                if (isFavorite) {
                    currentShow.favoritedAtMillis = System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        database.tvShowDao().insert(currentShow)
                        UserDataCache.addTvShowToFavorites(context, provider, currentShow)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        database.tvShowDao().delete(currentShow)
                        UserDataCache.removeTvShowFromFavorites(context, provider, currentShow.id)
                    }
                }
            }
            
            _uiState.value = _uiState.value.copy(show = currentShow)
        }
    }

    fun startDownload(server: Video.Server, type: Video.Type) {
        if (!Provider.supportsDownloads(UserPreferences.currentProvider)) {
            _uiState.value = _uiState.value.copy(isDownloading = false)
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isDownloading = true)
                val video = withContext(Dispatchers.IO) {
                    UserPreferences.currentProvider!!.getVideo(server)
                }

                val (downloadId, downloadTitle, downloadPoster) = when (type) {
                    is Video.Type.Movie -> Triple(type.id, type.title, type.poster)
                    is Video.Type.Episode -> {
                        val title = "${type.tvShow.title} - S${type.season.number}E${type.number}"
                        Triple(type.id, title, type.poster)
                    }
                }

                downloadManager.startDownload(
                    id = downloadId,
                    title = downloadTitle,
                    poster = downloadPoster,
                    url = video.source,
                    quality = DownloadQualityFormatter.qualityLabel(server, video),
                    headers = video.headers,
                    mimeType = video.type
                )
                _uiState.value = _uiState.value.copy(isDownloading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Download failed: ${e.message}", isDownloading = false)
            }
        }
    }

    fun loadServers(id: String, type: Video.Type) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(servers = emptyList(), error = null)
                val servers = withContext(Dispatchers.IO) {
                    UserPreferences.currentProvider!!.getServers(id, type)
                }
                _uiState.value = _uiState.value.copy(servers = servers)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }
}
