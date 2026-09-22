package com.nexastream.app.fragments.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.nexastream.app.R
import com.nexastream.app.databinding.DialogSearchFiltersTvBinding
import com.nexastream.app.models.SearchFilters
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

class SearchFilterTvDialog : DialogFragment() {

    private var _binding: DialogSearchFiltersTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels({ requireParentFragment() })
    private var currentFilters = SearchFilters()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.AppTheme_Tv)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFiltersTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentFilters = viewModel.filters.value
        setupUI()
    }

    private fun setupUI() {
        // Media Type
        updateMediaTypeUI()

        binding.btnMediaAll.setOnClickListener {
            currentFilters = currentFilters.copy(mediaType = SearchFilters.MediaType.ALL)
            updateMediaTypeUI()
        }
        binding.btnMediaMovies.setOnClickListener {
            currentFilters = currentFilters.copy(mediaType = SearchFilters.MediaType.MOVIES)
            updateMediaTypeUI()
        }
        binding.btnMediaTvShows.setOnClickListener {
            currentFilters = currentFilters.copy(mediaType = SearchFilters.MediaType.TV_SHOWS)
            updateMediaTypeUI()
        }

        // Sort By
        updateSortByButton()
        binding.btnSortBy.setOnClickListener {
            val options = SearchFilters.SortBy.entries.toTypedArray()
            val currentIndex = options.indexOf(currentFilters.sortBy)
            val nextIndex = (currentIndex + 1) % options.size
            currentFilters = currentFilters.copy(sortBy = options[nextIndex])
            updateSortByButton()
        }

        // Year
        updateYearButton()
        binding.btnYear.setOnClickListener {
            showYearSelection()
        }

        // Rating
        updateRatingButton()
        binding.btnRating.setOnClickListener {
            val nextRating = ((currentFilters.voteAverageGte ?: 0f) + 1.0f) % 11.0f
            currentFilters = currentFilters.copy(voteAverageGte = if (nextRating > 0) nextRating else null)
            updateRatingButton()
        }

        // Reset
        binding.btnReset.setOnClickListener {
            currentFilters = SearchFilters()
            viewModel.updateFilters(currentFilters)
            dismiss()
        }

        // Apply
        binding.btnApply.setOnClickListener {
            viewModel.updateFilters(currentFilters)
            dismiss()
        }
    }

    private fun updateMediaTypeUI() {
        val selectedId = when (currentFilters.mediaType) {
            SearchFilters.MediaType.ALL -> R.id.btn_media_all
            SearchFilters.MediaType.MOVIES -> R.id.btn_media_movies
            SearchFilters.MediaType.TV_SHOWS -> R.id.btn_media_tv_shows
        }

        listOf(binding.btnMediaAll, binding.btnMediaMovies, binding.btnMediaTvShows).forEach { btn ->
            val isSelected = btn.id == selectedId
            btn.setBackgroundResource(
                if (isSelected) R.color.netflix_red else R.drawable.bg_btn_exoplayer_tv
            )
        }
    }

    private fun updateSortByButton() {
        binding.btnSortBy.text = getString(R.string.search_filters_sort_by) + ": " + getSortByLabel(currentFilters.sortBy)
    }

    private fun updateYearButton() {
        val yearText = currentFilters.year?.toString() ?: getString(R.string.search_filters_any_year)
        binding.btnYear.text = getString(R.string.search_filters_year) + ": " + yearText
    }

    private fun updateRatingButton() {
        val ratingText = currentFilters.voteAverageGte?.let { getString(R.string.search_filters_rating_value, it) } 
            ?: "Any"
        binding.btnRating.text = getString(R.string.search_filters_rating) + ": " + ratingText
    }

    private fun showYearSelection() {
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val years = mutableListOf<String>()
        years.add(getString(R.string.search_filters_any_year))
        for (i in currentYear downTo 1900) {
            years.add(i.toString())
        }

        AlertDialog.Builder(requireContext(), R.style.Theme_Amoled_Dialog_Alert)
            .setTitle(R.string.search_filters_year)
            .setItems(years.toTypedArray()) { _, which ->
                currentFilters = currentFilters.copy(year = if (which == 0) null else years[which].toInt())
                updateYearButton()
            }
            .show()
    }

    private fun getSortByLabel(sortBy: SearchFilters.SortBy): String {
        return when (sortBy) {
            SearchFilters.SortBy.POPULARITY_DESC -> getString(R.string.search_filters_sort_popularity_desc)
            SearchFilters.SortBy.POPULARITY_ASC -> getString(R.string.search_filters_sort_popularity_asc)
            SearchFilters.SortBy.VOTE_AVERAGE_DESC -> getString(R.string.search_filters_sort_rating_desc)
            SearchFilters.SortBy.VOTE_AVERAGE_ASC -> getString(R.string.search_filters_sort_rating_asc)
            SearchFilters.SortBy.RELEASE_DATE_DESC -> getString(R.string.search_filters_sort_date_desc)
            SearchFilters.SortBy.RELEASE_DATE_ASC -> getString(R.string.search_filters_sort_date_asc)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
