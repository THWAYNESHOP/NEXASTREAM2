package com.nexastream.app.fragments.movies

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
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentMoviesTvBinding
import com.nexastream.app.models.Movie
import com.nexastream.app.providers.Provider
import com.nexastream.app.utils.UserPreferences
import com.nexastream.app.utils.CacheUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MoviesTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentMoviesTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MoviesViewModel by viewModels()

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoviesTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovies()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    MoviesViewModel.State.LoadingMore -> appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoading -> {
                        displayMovies(state.movies, state.hasMore)
                        appAdapter.isLoading = false
                        binding.vgvMovies.visibility = View.VISIBLE
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MoviesViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            if (appAdapter.isLoading) appAdapter.isLoading = false
                            viewModel.getMovies()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        if (appAdapter.isLoading) {
                            appAdapter.isLoading = false
                        } else {
                            binding.isLoading.apply {
                                pbIsLoading.visibility = View.GONE
                                gIsLoadingRetry.visibility = View.VISIBLE
                                btnIsLoadingRetry.setOnClickListener { viewModel.getMovies() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                    viewModel.getMovies()
                                }
                                binding.vgvMovies.visibility = View.GONE
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun initializeMovies() {
        binding.vgvMovies.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(requireContext().resources.getDimension(R.dimen.movies_spacing).toInt())
        }

        binding.root.requestFocus()
    }

    private fun displayMovies(movies: List<Movie>, hasMore: Boolean) {
        appAdapter.submitList(movies.onEach {
            it.itemType = AppAdapter.Type.MOVIE_GRID_TV_ITEM
        })

        if (hasMore) {
            appAdapter.setOnLoadMoreListener { viewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }
}
