package com.nexastream.app.fragments.tv_show

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
import com.nexastream.app.databinding.FragmentTvShowTvBinding
import com.nexastream.app.models.TvShow
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.loadTvShowBanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TvShowTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentTvShowTvBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<TvShowTvFragmentArgs>()
    private val viewModel: TvShowViewModel by viewModels()
    private val appAdapter = AppAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTvShowTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeTvShow()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is TvShowViewModel.State.SuccessLoading -> {
                        displayTvShow(state.tvShow)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is TvShowViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getTvShow()
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getTvShow() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                viewModel.getTvShow()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener { LoggingUtils.showErrorDialog(requireContext(), state.error) }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.vgvTvShow)
        _binding = null
    }

    private fun initializeTvShow() {
        binding.vgvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(80)
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivTvShowBanner.loadTvShowBanner(tvShow) {
            transition(DrawableTransitionOptions.withCrossFade())
        }
        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_TV },
            tvShow.takeIf { it.seasons.isNotEmpty() }?.copy()?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_TV },
            tvShow.takeIf { it.directors.isNotEmpty() }?.copy()?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_TV },
            tvShow.takeIf { it.cast.isNotEmpty() }?.copy()?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_TV },
            tvShow.takeIf { it.recommendations.isNotEmpty() }?.copy()?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_TV },
        ))
    }
}
