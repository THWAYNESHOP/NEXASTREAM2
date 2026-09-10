package com.nexastream.app.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchHistory
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SearchState {
    data object Searching : SearchState()
    data object SearchingMore : SearchState()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : SearchState()
    data class FailedSearching(val error: Exception) : SearchState()
    data object GlobalSearching : SearchState()
    data class SuccessGlobalSearching(val providerResults: List<SearchProviderResult>) : SearchState()
}

data class SearchProviderResult(
    val provider: Provider,
    val state: State,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val database: AppDatabase
) : ViewModel() {

    private val _state = MutableStateFlow<SearchState>(SearchState.Searching)

    val searchHistory: Flow<List<SearchHistory>> = database.searchHistoryDao().getRecent()
        .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<SearchState> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is SearchState.SuccessSearching -> {
                    val movies = state.results.filterIsInstance<Movie>()
                    if (movies.isEmpty()) emit(emptyList())
                    else emitAll(database.movieDao().getByIds(movies.map { it.id }))
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is SearchState.SuccessSearching -> {
                    val tvShows = state.results.filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) emit(emptyList())
                    else emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is SearchState.SuccessSearching -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }

                val enrichedResults = state.results.map { item ->
                    when (item) {
                        is Movie -> moviesById[item.id]?.takeIf { !item.isSame(it) }?.let { item.copy().merge(it) } ?: item
                        is TvShow -> tvShowsById[item.id]?.takeIf { !item.isSame(it) }?.let { item.copy().merge(it) } ?: item
                        else -> item
                    }
                }

                if (query.isNotEmpty() && enrichedResults.any { it is Movie || it is TvShow || it is People }) {
                    val categorized = mutableListOf<AppAdapter.Item>()
                    
                    val movies = enrichedResults.filterIsInstance<Movie>()
                    if (movies.isNotEmpty()) {
                        categorized.add(Category(name = "Movies", list = movies).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    val series = enrichedResults.filterIsInstance<TvShow>()
                    if (series.isNotEmpty()) {
                        categorized.add(Category(name = "TV Shows", list = series).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    val people = enrichedResults.filterIsInstance<People>()
                    if (people.isNotEmpty()) {
                        categorized.add(Category(name = "People", list = people).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }

                    val others = enrichedResults.filter { it !is Movie && it !is TvShow && it !is People }
                    if (others.isNotEmpty()) {
                         categorized.add(Category(name = "Other", list = others).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }

                    SearchState.SuccessSearching(results = categorized, hasMore = state.hasMore)
                } else {
                    SearchState.SuccessSearching(results = enrichedResults, hasMore = state.hasMore)
                }
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    init {
        search(query)
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(SearchState.Searching)
        try {
            if (query.isNotEmpty()) {
                database.searchHistoryDao().insert(SearchHistory(query))
            }
            val results = ParentalControlUtils.filterItems(UserPreferences.currentProvider!!.search(query))
            this@SearchViewModel.query = query
            page = 1
            _state.emit(SearchState.SuccessSearching(results, results.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search: ", e)
            _state.emit(SearchState.FailedSearching(e))
        }
    }

    fun deleteSearchHistory(query: String) = viewModelScope.launch(Dispatchers.IO) {
        database.searchHistoryDao().delete(query)
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is SearchState.SuccessSearching) {
            _state.emit(SearchState.SearchingMore)
            try {
                val results = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.search(query, page + 1)
                )
                val existingKeys = currentState.results.asSequence().map { it.searchIdentityKey() }.toHashSet()
                val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                page += 1
                _state.emit(
                    SearchState.SuccessSearching(
                        results = currentState.results + newUniqueResults,
                        hasMore = newUniqueResults.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.emit(SearchState.FailedSearching(e))
            }
        }
    }

    fun searchGlobal(query: String, currentLanguage: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(SearchState.GlobalSearching)
        val isCurrentProviderIptv = UserPreferences.currentProvider is IptvProvider
        val targetProviders = Provider.providers.keys
            .filter { it.language == currentLanguage && (it is IptvProvider) == isCurrentProviderIptv }
            .toList()

        if (targetProviders.isEmpty()) {
            _state.emit(SearchState.SuccessGlobalSearching(emptyList()))
            return@launch
        }

        val initialResults = targetProviders.map { SearchProviderResult(it, SearchProviderResult.State.Loading) }
        _state.emit(SearchState.SuccessGlobalSearching(initialResults))

        val mutableResults = initialResults.toMutableList()
        val stateComparator = compareBy<SearchProviderResult> {
            when (val state = it.state) {
                is SearchProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                is SearchProviderResult.State.Loading -> 2
                is SearchProviderResult.State.Error -> 4
            }
        }

        targetProviders.forEachIndexed { index, provider ->
            launch {
                try {
                    val results = ParentalControlUtils.filterItems(provider.search(query).onEach {
                        when (it) {
                            is Movie -> it.providerName = provider.name
                            is TvShow -> it.providerName = provider.name
                        }
                    })
                    mutableResults[index] = SearchProviderResult(provider, SearchProviderResult.State.Success(results))
                } catch (e: Exception) {
                    mutableResults[index] = SearchProviderResult(provider, SearchProviderResult.State.Error(e))
                }
                _state.emit(SearchState.SuccessGlobalSearching(mutableResults.sortedWith(stateComparator)))
            }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
