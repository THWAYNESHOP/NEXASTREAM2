package com.nexastream.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.adapters.AppAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val results: List<AppAdapter.Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: HomeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _uiState.value = SearchUiState()
            return
        }

        searchJob = viewModelScope.launch {
            delay(500) // Debounce
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val provider = UserPreferences.currentProvider ?: return@launch
                val results = repository.search(provider, query)
                _uiState.value = SearchUiState(results = results, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }
}
