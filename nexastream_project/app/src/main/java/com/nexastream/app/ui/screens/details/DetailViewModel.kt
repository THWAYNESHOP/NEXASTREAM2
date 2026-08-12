package com.nexastream.app.ui.screens.details

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder

data class DetailUiState(
    val isLoading: Boolean = false,
    val show: Show? = null,
    val error: String? = null
)

class DetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val context: Context,
    private val database: AppDatabase
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
                val dbMovie = database.movieDao().getById(id)
                val dbTvShow = database.tvShowDao().getById(id)

                val show = if (dbMovie != null) {
                    provider.getMovie(id).apply { 
                        isFavorite = dbMovie.isFavorite
                        favoritedAtMillis = dbMovie.favoritedAtMillis
                    }
                } else if (dbTvShow != null) {
                    provider.getTvShow(id).apply {
                        isFavorite = dbTvShow.isFavorite
                        favoritedAtMillis = dbTvShow.favoritedAtMillis
                    }
                } else {
                    // Try to fetch as Movie first, then TvShow
                    // Many providers encode the type in the URL, e.g. /movie/ or /tv/
                    if (id.contains("/movie/")) {
                        provider.getMovie(id)
                    } else if (id.contains("/series/") || id.contains("/tv/")) {
                        provider.getTvShow(id)
                    } else {
                        runCatching { provider.getMovie(id) }.getOrNull() 
                            ?: provider.getTvShow(id)
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
                    database.movieDao().insert(currentShow)
                    UserDataCache.addMovieToFavorites(context, provider, currentShow)
                } else {
                    database.movieDao().delete(currentShow)
                    UserDataCache.removeMovieFromFavorites(context, provider, currentShow.id)
                }
            } else if (currentShow is TvShow) {
                if (isFavorite) {
                    currentShow.favoritedAtMillis = System.currentTimeMillis()
                    database.tvShowDao().insert(currentShow)
                    UserDataCache.addTvShowToFavorites(context, provider, currentShow)
                } else {
                    database.tvShowDao().delete(currentShow)
                    UserDataCache.removeTvShowFromFavorites(context, provider, currentShow.id)
                }
            }
            
            _uiState.value = _uiState.value.copy(show = currentShow)
        }
    }
}
