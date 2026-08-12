package com.nexastream.app.fragments.season

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.adapters.AppAdapter
import com.nexastream.app.databinding.FragmentSeasonTvBinding
import com.nexastream.app.models.Episode
import com.nexastream.app.utils.CacheUtils
import com.nexastream.app.utils.LoggingUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SeasonTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false
    private var _binding: FragmentSeasonTvBinding? = null
    private val binding get() = _binding!!
    private val args by navArgs<SeasonTvFragmentArgs>()
    private val viewModel: SeasonViewModel by viewModels()
    private val appAdapter = AppAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeasonTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeSeason()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is SeasonViewModel.State.SuccessLoading -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is SeasonViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), Toast.LENGTH_SHORT).show()
                            viewModel.getEpisodes()
                            return@collect
                        }
                        Toast.makeText(requireContext(), state.error.message ?: "", Toast.LENGTH_SHORT).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getEpisodes() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                                viewModel.getEpisodes()
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
        appAdapter.onSaveInstanceState(binding.hgvEpisodes)
        _binding = null
    }

    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle
        binding.hgvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.season_episodes_spacing).toInt())
        }
    }

    private var focusedEpisodeIndex: Int? = null

    private fun displaySeason(episodes: List<Episode>) {
        val preparedEpisodes = episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_TV_ITEM
        }
        val lastWatchedIndex = episodes
            .filter { it.watchHistory != null }
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull()
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }

        appAdapter.submitList(preparedEpisodes)
        if (focusedEpisodeIndex == null) {
            val scrollIndex = when {
                lastWatchedIndex == -1 -> 0
                lastWatchedIndex < episodes.lastIndex -> lastWatchedIndex + 1
                else -> lastWatchedIndex
            }
            binding.hgvEpisodes.scrollAndFocus(scrollIndex)
            focusedEpisodeIndex = scrollIndex
        }
    }

    private fun RecyclerView.scrollAndFocus(position: Int) {
        scrollToPosition(position)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        })
    }
}
