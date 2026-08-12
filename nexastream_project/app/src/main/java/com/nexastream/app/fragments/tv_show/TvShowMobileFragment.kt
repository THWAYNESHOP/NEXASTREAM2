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
import com.nexastream.app.databinding.FragmentTvShowMobileBinding
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import com.nexastream.app.utils.dp
import com.nexastream.app.utils.loadTvShowBanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TvShowMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentTvShowMobileBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<TvShowMobileFragmentArgs>()
    private val viewModel: TvShowViewModel by viewModels()

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTvShowMobileBinding.inflate(inflater, container, false)
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
                            android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getTvShow()
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
                                val doRetry = { viewModel.getTvShow() }
                                btnIsLoadingRetry.setOnClickListener { doRetry() }
                                btnIsLoadingClearCache.setOnClickListener {
                                    CacheUtils.clearAppCache(requireContext())
                                    android.widget.Toast.makeText(requireContext(), getString(com.nexastream.app.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
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
        appAdapter.onSaveInstanceState(binding.rvTvShow)
        _binding = null
    }


    private fun initializeTvShow() {
        binding.rvTvShow.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }
    }

    private fun displayTvShow(tvShow: TvShow) {
        binding.ivTvShowBanner.loadTvShowBanner(tvShow) {
            transition(DrawableTransitionOptions.withCrossFade())
        }

        appAdapter.submitList(listOfNotNull(
            tvShow.apply { itemType = AppAdapter.Type.TV_SHOW_MOBILE },

            tvShow.takeIf { it.seasons.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_SEASONS_MOBILE },

            tvShow.takeIf { it.directors.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_DIRECTORS_MOBILE },

            tvShow.takeIf { it.cast.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_CAST_MOBILE },

            tvShow.takeIf { it.recommendations.isNotEmpty() }
                ?.copy()
                ?.apply { itemType = AppAdapter.Type.TV_SHOW_RECOMMENDATIONS_MOBILE },
        ))
    }
}
