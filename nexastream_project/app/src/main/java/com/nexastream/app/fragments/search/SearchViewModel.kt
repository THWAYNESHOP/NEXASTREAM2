package com.nexastream.app.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.models.SearchHistory
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.IptvProvider
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.TMDb3
import com.nexastream.app.utils.TMDb3.original
import com.nexastream.app.utils.TMDb3.w500
import com.nexastream.app.utils.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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

    private val _filters = MutableStateFlow(SearchFilters())
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    private val _trending = MutableStateFlow<List<AppAdapter.Item>>(emptyList())
    val trending: StateFlow<List<AppAdapter.Item>> = _trending.asStateFlow()

    private val _topRated = MutableStateFlow<List<AppAdapter.Item>>(emptyList())
    val topRated: StateFlow<List<AppAdapter.Item>> = _topRated.asStateFlow()

    private val _airingToday = MutableStateFlow<List<AppAdapter.Item>>(emptyList())
    val airingToday: StateFlow<List<AppAdapter.Item>> = _airingToday.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val suggestionQuery = MutableStateFlow("")
    private val searchQuery = MutableStateFlow("")

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
                    
                    val movies = enrichedResults.filterIsInstance<Movie>().onEach { 
                        it.itemType = AppAdapter.Type.MOVIE_TV_ITEM 
                    }
                    if (movies.isNotEmpty()) {
                        categorized.add(Category(name = "Movies", list = movies).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    val series = enrichedResults.filterIsInstance<TvShow>().onEach { 
                        it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM 
                    }
                    if (series.isNotEmpty()) {
                        categorized.add(Category(name = "TV Shows", list = series).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    val people = enrichedResults.filterIsInstance<People>().onEach { 
                        it.itemType = AppAdapter.Type.PEOPLE_TV_ITEM 
                    }
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
        fetchDiscovery()
        setupSuggestions()
        setupSearchFlow()
    }

    private fun fetchDiscovery() = viewModelScope.launch(Dispatchers.IO) {
        val lang = UserPreferences.currentLanguage ?: "en"

        launch {
            try {
                val results = TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, page = 1, language = lang)
                    .results.mapNotNull(::mapMulti)
                _trending.emit(ParentalControlUtils.filterItems(results))
            } catch (e: Exception) { Log.e("SearchViewModel", "trending: ", e) }
        }

        launch {
            try {
                val results = TMDb3.MovieLists.topRated(page = 1, language = lang)
                    .results.map { movie ->
                        Movie(
                            id = movie.id.toString(),
                            title = movie.title,
                            overview = movie.overview,
                            released = movie.releaseDate,
                            rating = movie.voteAverage.toDouble(),
                            poster = movie.posterPath?.w500,
                            banner = movie.backdropPath?.original,
                        )
                    }
                _topRated.emit(ParentalControlUtils.filterItems(results))
            } catch (e: Exception) { Log.e("SearchViewModel", "topRated: ", e) }
        }

        launch {
            try {
                val results = TMDb3.TvSeriesLists.airingToday(page = 1, language = lang)
                    .results.map { tv ->
                        TvShow(
                            id = tv.id.toString(),
                            title = tv.name,
                            overview = tv.overview,
                            released = tv.firstAirDate,
                            rating = tv.voteAverage.toDouble(),
                            poster = tv.posterPath?.w500,
                            banner = tv.backdropPath?.original,
                        )
                    }
                _airingToday.emit(ParentalControlUtils.filterItems(results))
            } catch (e: Exception) { Log.e("SearchViewModel", "airingToday: ", e) }
        }
    }

    private fun mapMulti(multi: TMDb3.MultiItem): AppAdapter.Item? = when (multi) {
        is TMDb3.Movie -> Movie(
            id = multi.id.toString(),
            title = multi.title,
            overview = multi.overview,
            released = multi.releaseDate,
            rating = multi.voteAverage.toDouble(),
            poster = multi.posterPath?.w500,
            banner = multi.backdropPath?.original,
        )
        is TMDb3.Tv -> TvShow(
            id = multi.id.toString(),
            title = multi.name,
            overview = multi.overview,
            released = multi.firstAirDate,
            rating = multi.voteAverage.toDouble(),
            poster = multi.posterPath?.w500,
            banner = multi.backdropPath?.original,
        )
        is TMDb3.Person -> People(
            id = multi.id.toString(),
            name = multi.name,
            image = multi.profilePath?.w500,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun setupSuggestions() {
        suggestionQuery
            .debounce(300)
            .distinctUntilChanged()
            .onEach { query ->
                if (query.length < 2) {
                    _suggestions.emit(emptyList())
                    return@onEach
                }
                try {
                    val lang = UserPreferences.currentLanguage ?: "en"
                    val results = TMDb3.Search.multi(query, language = lang).results
                    val titles = results.mapNotNull { multi ->
                        when (multi) {
                            is TMDb3.Movie -> multi.title
                            is TMDb3.Tv -> multi.name
                            is TMDb3.Person -> multi.name
                        }
                    }.distinct().take(10)
                    _suggestions.emit(titles)
                } catch (_: Exception) {
                    _suggestions.emit(emptyList())
                }
            }
            .launchIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun setupSearchFlow() {
        searchQuery
            .debounce(400)
            .distinctUntilChanged()
            .onEach { query ->
                performSearch(query)
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChanged(newQuery: String) {
        suggestionQuery.value = newQuery
        searchQuery.value = newQuery
    }

    fun search(query: String) {
        searchQuery.value = query
    }

    fun searchImmediate(query: String) {
        viewModelScope.launch {
            performSearch(query)
        }
    }

    private fun performSearch(query: String) = viewModelScope.launch(Dispatchers.IO) {
        if (this@SearchViewModel.query == query && query.isNotEmpty()) return@launch
        
        this@SearchViewModel.query = query
        _state.emit(SearchState.Searching)
        try {
            if (query.isNotEmpty()) {
                database.searchHistoryDao().insert(SearchHistory(query))
            }
            val results = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.search(query, filters = _filters.value)
            )
            page = 1
            _state.emit(SearchState.SuccessSearching(results, results.isNotEmpty()))
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search: ", e)
            _state.emit(SearchState.FailedSearching(e))
        }
    }

    fun updateFilters(filters: SearchFilters) {
        _filters.value = filters
        search(query)
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
                    UserPreferences.currentProvider!!.search(query, page + 1, filters = _filters.value)
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
