package com.nexastream.app.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentHomeMobileBinding
import com.nexastream.app.models.Category
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.TvShow
import com.nexastream.app.repositories.HomeRepository
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.ProviderChangeNotifier
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.viewModelsFactory
import kotlinx.coroutines.launch

class HomeMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModelsFactory {
        HomeViewModel(
            AppDatabase.getInstance(requireContext()),
            HomeRepository(requireContext(), AppDatabase.getInstance(requireContext()))
        )
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { viewModel.getHome() }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            val doRetry = { viewModel.getHome() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                doRetry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvHome)
        _binding = null
    }


    private fun initializeHome() {
        binding.rvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }

        binding.ivProviderLogo.apply {
            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                    ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .into(this)

            setOnClickListener {
                findNavController().navigate(R.id.providers)
            }
        }
        
        // Ensure background image is hidden on mobile to show theme color
        binding.ivHomeBackground.visibility = View.GONE
    }

    private fun displayHome(categories: List<Category>) {
        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                it.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != Category.FEATURED && category.name != getString(R.string.home_continue_watching)) {
                        category.list.onEach { show ->
                            when (show) {
                                is Episode -> show.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
                                is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }
                    }
                    category.itemSpacing = 10.dp(requireContext())
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                        else -> AppAdapter.Type.CATEGORY_MOBILE_ITEM
                    }
                }
        )
    }
}
