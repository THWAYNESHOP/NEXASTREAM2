package com.nexastream.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.Show
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.adapters.AppAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.nexastream.app.models.Episode
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.UserDataCache.toEpisode

data class HomeUiState(
    val isLoading: Boolean = false,
    val categories: List<Category> = emptyList(),
    val heroMovie: Show? = null,
    val continueWatching: List<Show> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val homeRepository: HomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    private var userDataJob: Job? = null

    init {
        loadHomeData()
        observeUserData()
        observeProviderChange()
    }

    private fun observeProviderChange() {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collectLatest {
                homeRepository.clearCache()
                loadHomeData()
                observeUserData()
            }
        }
    }

    private fun observeUserData() {
        userDataJob?.cancel()
        val provider = UserPreferences.currentProvider ?: return
        userDataJob = viewModelScope.launch {
            homeRepository.getUserDataFlow(provider).collectLatest { userData ->
                userData?.let { data ->
                    val movies = data.continueWatchingMovies.map { it.toMovie() }
                    val episodes = data.continueWatchingEpisodes.map { it.toEpisode() }

                    val combined = (movies + episodes.mapNotNull { it.tvShow }).distinctBy { it.id }
                    
                    combined.forEach { 
                        if (it is Movie) it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        if (it is TvShow) it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }

                    _uiState.update { it.copy(continueWatching = combined) }
                }
            }
        }
    }

    fun loadHomeData() {
        val provider = UserPreferences.currentProvider ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val categories = homeRepository.getHome(provider)
                val filteredCategories = ParentalControlUtils.filterCategories(categories)
                
                filteredCategories.forEach { category ->
                    category.list.forEach { item ->
                        if (item is Movie) item.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
                        if (item is TvShow) item.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                }
                
                val heroMovie = filteredCategories.firstOrNull()?.list?.filterIsInstance<Show>()?.firstOrNull()
                
                _uiState.update { 
                    it.copy(
                        categories = filteredCategories,
                        heroMovie = heroMovie,
                        isLoading = false,
                        error = null
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    ) 
                }
            }
        }
    }
}
