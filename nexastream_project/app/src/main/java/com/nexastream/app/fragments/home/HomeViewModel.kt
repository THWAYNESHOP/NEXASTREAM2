package com.nexastream.app.fragments.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.providers.Provider
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.utils.ParentalControlUtils
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserDataCache
import com.nexastream.app.utils.UserDataCache.toEpisode
import com.nexastream.app.utils.UserDataCache.toMovie
import com.nexastream.app.utils.UserDataCache.toTvShow
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.combine6
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val database: AppDatabase,
    private val repository: HomeRepository
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine6(
        _state,
        // CONTINUE WATCHING
        combine(
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                    emit(cache.continueWatchingMovies.map { it.toMovie() })
                } else {
                    emitAll(database.movieDao().getWatchingMovies())
                }
            },
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getWatchingEpisodes())
                }
            },
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getNextEpisodesToWatch())
                }
            },
            database.tvShowDao().getAll(),
        ) { watchingMovies: List<Movie>, watchingEpisodes: List<Episode>, watchNextEpisodes: List<Episode>, tvShows: List<TvShow> ->
            val allEpisodes = (watchingEpisodes + watchNextEpisodes).distinctBy { e -> e.id }
            val tvShowsMap = tvShows.associateBy { t -> t.id }

            val seasonIds = allEpisodes.mapNotNull { e -> e.season?.id }.distinct()
            val seasonsMap = if (seasonIds.isEmpty()) emptyMap() else {
                database.seasonDao().getByIds(seasonIds).associateBy { s -> s.id }
            }

            val enrichedEpisodes = currentProvider?.let { provider ->
                repository.enrichContinueWatchingEpisodes(
                    provider,
                    allEpisodes.map { episode ->
                        episode.copy(
                            tvShow = episode.tvShow?.id?.let { id -> tvShowsMap[id] } ?: episode.tvShow,
                            season = episode.season?.id?.let { id -> seasonsMap[id] } ?: episode.season,
                        ).apply { merge(episode) }
                    }
                )
            } ?: emptyList()

            (watchingMovies + enrichedEpisodes)
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis ?: item.watchedDate?.timeInMillis ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis ?: item.watchedDate?.timeInMillis ?: 0L
                        else -> 0L
                    }
                } as List<AppAdapter.Item>
        }.flowOn(Dispatchers.IO),

        // FAVORITES
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesMovies.isNotEmpty()) {
                emit(cache.favoritesMovies.map { it.toMovie() })
            } else {
                emitAll(database.movieDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),
        _userDataCache.transformLatest { cache ->
            if (cache != null && cache.favoritesTvShows.isNotEmpty()) {
                emit(cache.favoritesTvShows.map { it.toTvShow() })
            } else {
                emitAll(database.tvShowDao().getFavorites())
            }
        }.flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            if (state is State.SuccessLoading) {
                val ids = state.categories.flatMap { it.list }.filterIsInstance<Movie>().map { it.id }
                if (ids.isEmpty()) emit(emptyList()) else emitAll(database.movieDao().getByIds(ids))
            } else emit(emptyList<Movie>())
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            if (state is State.SuccessLoading) {
                val ids = state.categories.flatMap { it.list }.filterIsInstance<TvShow>().map { it.id }
                if (ids.isEmpty()) emit(emptyList()) else emitAll(database.tvShowDao().getByIds(ids))
            } else emit(emptyList<TvShow>())
        }.flowOn(Dispatchers.IO),

    ) { state: State, continueWatching: List<AppAdapter.Item>, favoritesMovies: List<Movie>, favoriteTvShows: List<TvShow>, moviesDb: List<Movie>, tvShowsDb: List<TvShow> ->
        if (state is State.SuccessLoading) {
            val moviesMap = moviesDb.associateBy { m -> m.id }
            val tvShowsMap = tvShowsDb.associateBy { t -> t.id }

            fun mergeItem(item: AppAdapter.Item): AppAdapter.Item = when (item) {
                is Movie -> moviesMap[item.id]?.takeIf { m -> !item.isSame(m) }?.let { m -> item.copy().merge(m) } ?: item
                is TvShow -> tvShowsMap[item.id]?.takeIf { t -> !item.isSame(t) }?.let { t -> item.copy().merge(t) } ?: item
                else -> item
            }

            val categories = mutableListOf<Category>()
            state.categories.find { c -> c.name == Category.FEATURED }?.let { c ->
                categories.add(c.copy(list = c.list.map(::mergeItem))) 
            }
            
            categories.add(Category(
                name = Category.CONTINUE_WATCHING,
                list = continueWatching.distinctBy { item ->
                    when (item) {
                        is Episode -> item.tvShow?.id ?: item.id
                        is Movie -> item.id
                        else -> item.hashCode().toString()
                    }
                }
            ))
            
            categories.add(Category(name = Category.FAVORITE_MOVIES, list = favoritesMovies))
            categories.add(Category(name = Category.FAVORITE_TV_SHOWS, list = favoriteTvShows))
            
            categories.addAll(state.categories.filter { c -> c.name != Category.FEATURED }.map { c -> c.copy(list = c.list.map(::mergeItem)) })

            State.SuccessLoading(ParentalControlUtils.filterCategories(categories))
        } else state
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            observeUserData(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                currentProvider = provider
                observeUserData(provider)
                getHome()
            }
        }
        getHome()
    }

    private var userDataJob: Job? = null
    private fun observeUserData(provider: Provider) {
        userDataJob?.cancel()
        userDataJob = viewModelScope.launch(Dispatchers.IO) {
            repository.getUserDataFlow(provider).collect {
                _userDataCache.value = it
            }
        }
    }

    fun getHome() = viewModelScope.launch(Dispatchers.IO) {
        val provider = currentProvider ?: return@launch
        _state.emit(State.Loading)

        val cached = repository.getCachedHome(provider)
        if (!cached.isNullOrEmpty()) {
            _state.emit(State.SuccessLoading(cached))
        }

        try {
            val categories = repository.getHome(provider)
            _state.emit(State.SuccessLoading(categories))
            repository.updateUserDataCache(provider, _userDataCache.value)
        } catch (e: Exception) {
            Log.e("HomeViewModel", "getHome failed", e)
            if (_state.value !is State.SuccessLoading) {
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
