package com.nexastream.app.fragments.movie

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
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.databinding.FragmentMovieMobileBinding
import com.nexastream.app.models.Movie
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.loadMovieBanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MovieMobileFragment : Fragment() {

    private var _binding: FragmentMovieMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<MovieMobileFragmentArgs>()
    private val viewModel: MovieViewModel by viewModels()

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMovieMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeMovie()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MovieViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is MovieViewModel.State.SuccessLoading -> {
                        displayMovie(state.movie)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is MovieViewModel.State.FailedLoading -> {
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                            binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                                val doRetry = { viewModel.getMovie() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
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
        appAdapter.onSaveInstanceState(binding.rvMovie)
        _binding = null
    }


    private fun initializeMovie() {
        binding.rvMovie.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displayMovie(movie: Movie) {
        binding.ivMovieBanner.loadMovieBanner(movie) {
            transition(DrawableTransitionOptions.withCrossFade())
        }

        appAdapter.submitList(listOfNotNull(
            movie.apply { itemType = AppAdapter.Type.MOVIE_MOBILE },

            movie.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_DIRECTORS_MOBILE },

            movie.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_CAST_MOBILE },

            movie.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.MOVIE_RECOMMENDATIONS_MOBILE },
        ))
    }
}
