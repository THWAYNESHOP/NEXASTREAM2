package com.nexastream.app.fragments.search

import android.os.Bundle
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentSearchTvBinding
import com.nexastream.app.databinding.ItemSearchSuggestionTvBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Genre
import com.nexastream.app.models.Movie
import com.nexastream.app.models.People
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.VoiceRecognitionHelper
import com.nexastream.app.utils.hideKeyboard
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import androidx.navigation.fragment.findNavController
import com.nexastream.app.providers.Provider
import com.nexastream.app.providers.IptvProvider

@AndroidEntryPoint
class SearchTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSearchTvBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchViewModel by viewModels()
    private var isGlobalSearchChecked: Boolean = false
    private var currentGridColumns: Int = 1

    private val suggestionsAdapter by lazy {
        SuggestionsAdapter { suggestion ->
            binding.etSearch.setText(suggestion)
            submitSearch()
        }
    }

    private val appAdapter by lazy {
        AppAdapter().apply {
            onMovieClickListener = { movie ->
                if (movie.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == movie.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, movie.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(SearchTvFragmentDirections.actionSearchToMovie(id = movie.id))
            }
            onTvShowClickListener = { tvShow ->
                if (tvShow.providerName != UserPreferences.currentProvider?.name) {
                    UserPreferences.currentProvider = Provider.providers.keys.find { it.name == tvShow.providerName }
                    Toast.makeText(requireContext(), getString(R.string.switching_to_provider, tvShow.providerName), Toast.LENGTH_SHORT).show()
                }
                findNavController().navigate(SearchTvFragmentDirections.actionSearchToTvShow(id = tvShow.id, poster = tvShow.poster, banner = tvShow.banner))
            }
            onPeopleClickListener = { people ->
                findNavController().navigate(SearchTvFragmentDirections.actionSearchToPeople(id = people.id, name = people.name, image = people.image))
            }
        }
    }

    private val voiceHelper by lazy {
        VoiceRecognitionHelper(
            fragment = this,
            onResult = { query ->
                binding.btnSearchVoice.clearAnimation()
                binding.etSearch.setText(query)
                viewModel.search(query)
            },
            onError = { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                binding.btnSearchVoice.clearAnimation()
                val isIptv = UserPreferences.currentProvider is IptvProvider
                binding.etSearch.hint = getString(if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint)
            },
            onListeningStateChanged = { isListening ->
                binding.btnSearchVoice.startAnimation(AlphaAnimation(1f, 0.3f).apply {
                    duration = 500
                    repeatCount = Animation.INFINITE
                    repeatMode = Animation.REVERSE
                })
                binding.etSearch.hint = getString(R.string.voice_prompt)
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeSearch()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filters.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { filters ->
                val isActive = !filters.isDefault()
                binding.btnSearchFilter.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isActive) com.nexastream.app.utils.ThemeManager.palette(UserPreferences.selectedTheme).mobileNavActive
                    else android.graphics.Color.WHITE
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.searchHistory.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.trending.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.topRated.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED),
                viewModel.airingToday.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            ) { historyList, trendingList, topRatedList, airingTodayList ->
                if (viewModel.query.isEmpty() && viewModel.filters.value.isDefault()) {
                    val categories = mutableListOf<Category>()
                    
                    if (historyList.isNotEmpty()) {
                        categories.add(Category(
                            name = getString(R.string.search_recent),
                            list = historyList.map { 
                                Genre(id = it.query, name = it.query).apply { itemType = AppAdapter.Type.GENRE_GRID_TV_ITEM }
                            }
                        ).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    if (trendingList.isNotEmpty()) {
                        categories.add(Category(
                            name = "Trending Now",
                            list = trendingList.onEach {
                                when (it) {
                                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                                }
                            }
                        ).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }

                    if (topRatedList.isNotEmpty()) {
                        categories.add(Category(
                            name = "Top Rated",
                            list = topRatedList.onEach {
                                when (it) {
                                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                                }
                            }
                        ).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }

                    if (airingTodayList.isNotEmpty()) {
                        categories.add(Category(
                            name = "Airing Today",
                            list = airingTodayList.onEach {
                                when (it) {
                                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                                }
                            }
                        ).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM })
                    }
                    
                    categories
                } else {
                    emptyList<Category>()
                }
            }.collect { categories ->
                if (viewModel.query.isEmpty() && viewModel.filters.value.isDefault() && categories.isNotEmpty()) {
                    currentGridColumns = 1
                    binding.vgvSearch.setNumColumns(currentGridColumns)
                    appAdapter.submitList(categories)
                    appAdapter.onGenreClickListener = { genre ->
                        binding.etSearch.setText(genre.name)
                        submitSearch()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.suggestions.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { suggestions ->
                binding.hgvSuggestions.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
                suggestionsAdapter.submitList(suggestions)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is SearchState.Searching, is SearchState.GlobalSearching -> {
                        binding.isLoading.apply {
                            root.visibility = View.VISIBLE
                            pbIsLoading.visibility = View.VISIBLE
                            gIsLoadingRetry.visibility = View.GONE
                        }
                        appAdapter.isLoading = false
                        appAdapter.setOnLoadMoreListener(null)
                    }
                    is SearchState.SearchingMore -> appAdapter.isLoading = true
                    is SearchState.SuccessSearching -> {
                        displaySearch(state.results, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                        binding.keyboardView.visibility = View.VISIBLE
                        binding.llKeyboardActions.visibility = View.VISIBLE
                    }
                    is SearchState.SuccessGlobalSearching -> {
                        displayGlobalSearch(state.providerResults)
                        appAdapter.isLoading = false
                        binding.vgvSearch.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                        binding.keyboardView.visibility = View.VISIBLE
                        binding.llKeyboardActions.visibility = View.VISIBLE
                    }
                    is SearchState.FailedSearching -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.search(viewModel.query)
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.search(viewModel.query) }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                    viewModel.search(viewModel.query)
                                }
                                btnIsLoadingErrorDetails.setOnClickListener { LoggingUtils.showErrorDialog(requireContext(), state.error) }
                                binding.vgvSearch.visibility = View.INVISIBLE
                                binding.etSearch.nextFocusDownId = binding.isLoading.btnIsLoadingRetry.id
                                binding.isLoading.btnIsLoadingRetry.nextFocusUpId = binding.etSearch.id
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        voiceHelper.stopRecognition()
        _binding = null
    }

    private fun submitSearch(): Boolean {
        val query = binding.etSearch.text?.toString().orEmpty()
        hideKeyboard()
        if (isGlobalSearchChecked) {
            if (query.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.search_empty_query), Toast.LENGTH_SHORT).show()
                return true
            }
            val currentLanguage = UserPreferences.currentProvider?.language ?: "es"
            viewModel.searchGlobal(query, currentLanguage)
        } else {
            viewModel.searchImmediate(query)
        }
        return true
    }

    private fun initializeSearch() {
        val isIptv = UserPreferences.currentProvider is IptvProvider
        binding.etSearch.hint = getString(if (isIptv) R.string.search_input_hint_iptv else R.string.search_input_hint)

        binding.keyboardView.onKeyClickListener = { key ->
            val currentText = binding.etSearch.text?.toString().orEmpty()
            val newQuery = currentText + key
            binding.etSearch.setText(newQuery)
            viewModel.onQueryChanged(newQuery)
            viewModel.search(newQuery)
        }

        binding.keyboardView.onBackPressListener = {
            if (appAdapter.itemCount > 0) {
                binding.vgvSearch.requestFocus()
                true
            } else false
        }

        binding.btnKeySpace.setOnClickListener {
            val currentText = binding.etSearch.text?.toString().orEmpty()
            val newQuery = "$currentText "
            binding.etSearch.setText(newQuery)
            viewModel.search(newQuery)
        }

        binding.btnKeyDelete.setOnClickListener {
            val currentText = binding.etSearch.text?.toString().orEmpty()
            if (currentText.isNotEmpty()) {
                val newQuery = currentText.substring(0, currentText.length - 1)
                binding.etSearch.setText(newQuery)
                viewModel.onQueryChanged(newQuery)
                viewModel.search(newQuery)
            }
        }

        binding.hgvSuggestions.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            adapter = suggestionsAdapter
        }

        binding.llGlobalSearch.setOnClickListener {
            isGlobalSearchChecked = !isGlobalSearchChecked
            binding.ivGlobalSearchSwitch.setImageResource(if (isGlobalSearchChecked) R.drawable.ic_switch_on else R.drawable.ic_switch_off)
        }

        binding.btnSearchVoice.apply {
            visibility = if (voiceHelper.isAvailable()) View.VISIBLE else View.GONE
            setOnClickListener { if (!voiceHelper.isListening) voiceHelper.startWithPermissionCheck() }
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.setText("")
            val isIptvHint = UserPreferences.currentProvider is IptvProvider
            binding.etSearch.hint = getString(if (isIptvHint) R.string.search_input_hint_iptv else R.string.search_input_hint)
            appAdapter.onGenreClickListener = null
            viewModel.searchImmediate("")
        }

        binding.btnSearchFilter.setOnClickListener {
            SearchFilterTvDialog().show(childFragmentManager, "SearchFilter")
        }

        binding.vgvSearch.apply {
            adapter = appAdapter
            setItemSpacing(resources.getDimension(R.dimen.search_spacing).toInt())
            addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(parent: RecyclerView, child: RecyclerView.ViewHolder?, position: Int, subposition: Int) {
                    // child?.itemView?.nextFocusLeftId = binding.keyboardView.id // Optional
                }
            })
        }
        binding.keyboardView.requestFocus()
    }

    private fun focusSearchContent(): Boolean {
        val hasResults = appAdapter.itemCount > 0 && binding.vgvSearch.visibility == View.VISIBLE
        return when {
            hasResults -> { binding.vgvSearch.requestFocus(); true }
            binding.llGlobalSearch.visibility == View.VISIBLE -> { binding.llGlobalSearch.requestFocus(); true }
            else -> false
        }
    }

    private fun displaySearch(list: List<AppAdapter.Item>, hasMore: Boolean) {
        if (viewModel.query.isEmpty()) return

        // Always use 1 column for categorized search results on TV to prevent "cracking"
        currentGridColumns = 1
        binding.vgvSearch.setNumColumns(currentGridColumns)
        
        appAdapter.submitList(list)
        
        if (hasMore && viewModel.query != "") {
            appAdapter.setOnLoadMoreListener { viewModel.loadMore() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun displayGlobalSearch(providerResults: List<SearchProviderResult>) {
        val categories = providerResults.map { providerResult ->
            val headerTitle = when (val state = providerResult.state) {
                is SearchProviderResult.State.Loading -> "${providerResult.provider.name} - ${getString(R.string.searching)}"
                is SearchProviderResult.State.Error -> "${providerResult.provider.name} - ${getString(R.string.search_error)}"
                is SearchProviderResult.State.Success -> {
                    val count = state.results.size
                    val resultText = if (count == 1) getString(R.string.result) else getString(R.string.results)
                    "${providerResult.provider.name} - $count $resultText"
                }
            }
            val items = (providerResult.state as? SearchProviderResult.State.Success)?.results?.onEach {
                when (it) {
                    is Movie -> it.itemType = AppAdapter.Type.MOVIE_TV_ITEM
                    is TvShow -> it.itemType = AppAdapter.Type.TV_SHOW_TV_ITEM
                }
            } ?: emptyList()
            Category(name = headerTitle, list = items).apply { itemType = AppAdapter.Type.CATEGORY_TV_ITEM }
        }
        currentGridColumns = 1
        binding.vgvSearch.setNumColumns(currentGridColumns)
        appAdapter.submitList(categories)
        appAdapter.setOnLoadMoreListener(null)
    }

    private class SuggestionsAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<SuggestionViewHolder>() {
        private val items = mutableListOf<String>()

        fun submitList(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
            return SuggestionViewHolder(
                ItemSearchSuggestionTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                onClick
            )
        }

        override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private class SuggestionViewHolder(
        private val binding: ItemSearchSuggestionTvBinding,
        private val onClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(suggestion: String) {
            binding.root.apply {
                text = suggestion
                setOnClickListener { onClick(suggestion) }
            }
        }
    }
}
