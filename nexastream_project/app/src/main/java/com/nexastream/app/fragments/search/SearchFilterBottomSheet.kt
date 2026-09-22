package com.nexastream.app.fragments.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.nexastream.app.R
import com.nexastream.app.databinding.DialogSearchFiltersMobileBinding
import com.nexastream.app.models.SearchFilters
import com.nexastream.app.utils.TMDb3
import com.nexastream.app.utils.UserPreferences
import kotlinx.coroutines.launch

class SearchFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogSearchFiltersMobileBinding? = null
    private val binding get() = _binding!!

    // Scope to parent fragment to share the same ViewModel instance
    private val viewModel: SearchViewModel by viewModels({ requireParentFragment() })

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSearchFiltersMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        loadCurrentFilters()
        fetchGenres()
    }

    private fun setupUI() {
        // Sort By Spinner
        val sortByOptions = SearchFilters.SortBy.entries.toTypedArray()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            sortByOptions.map { getSortByLabel(it) }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spSortBy.adapter = adapter

        // Rating Slider
        binding.sliderRating.addOnChangeListener { _, value, _ ->
            binding.tvRatingValue.text = getString(R.string.search_filters_rating_value, value)
        }

        // Apply Button
        binding.btnApply.setOnClickListener {
            applyFilters()
            dismiss()
        }

        // Reset Button
        binding.btnReset.setOnClickListener {
            viewModel.updateFilters(SearchFilters())
            dismiss()
        }
    }

    private fun loadCurrentFilters() {
        val filters = viewModel.filters.value

        // Media Type
        when (filters.mediaType) {
            SearchFilters.MediaType.ALL -> binding.chipMediaAll.isChecked = true
            SearchFilters.MediaType.MOVIES -> binding.chipMediaMovies.isChecked = true
            SearchFilters.MediaType.TV_SHOWS -> binding.chipMediaTvShows.isChecked = true
        }

        // Sort By
        val sortByOptions = SearchFilters.SortBy.entries.toTypedArray()
        val index = sortByOptions.indexOf(filters.sortBy)
        if (index >= 0) binding.spSortBy.setSelection(index)

        // Year
        binding.etYear.setText(filters.year?.toString() ?: "")

        // Rating
        val rating = filters.voteAverageGte ?: 0.0f
        binding.sliderRating.value = rating
        binding.tvRatingValue.text = getString(R.string.search_filters_rating_value, rating)
    }

    private fun fetchGenres() {
        lifecycleScope.launch {
            try {
                val lang = UserPreferences.currentLanguage ?: "en"
                val movieGenres = TMDb3.Genres.movieList(lang).genres
                val tvGenres = TMDb3.Genres.tvList(lang).genres
                val allGenres = (movieGenres + tvGenres).distinctBy { it.id }.sortedBy { it.name }

                val currentGenres = viewModel.filters.value.genres

                allGenres.forEach { genre ->
                    val chip = Chip(requireContext()).apply {
                        text = genre.name
                        isCheckable = true
                        isChecked = currentGenres.contains(genre.id.toString())
                        tag = genre.id.toString()
                    }
                    binding.cgGenres.addView(chip)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun applyFilters() {
        val mediaType = when (binding.cgMediaType.checkedChipId) {
            R.id.chip_media_movies -> SearchFilters.MediaType.MOVIES
            R.id.chip_media_tv_shows -> SearchFilters.MediaType.TV_SHOWS
            else -> SearchFilters.MediaType.ALL
        }

        val sortBy = SearchFilters.SortBy.entries[binding.spSortBy.selectedItemPosition]
        val year = binding.etYear.text.toString().toIntOrNull()
        val rating = binding.sliderRating.value.takeIf { it > 0 }
        
        val selectedGenres = mutableListOf<String>()
        for (i in 0 until binding.cgGenres.childCount) {
            val chip = binding.cgGenres.getChildAt(i) as Chip
            if (chip.isChecked) {
                selectedGenres.add(chip.tag.toString())
            }
        }

        val newFilters = SearchFilters(
            genres = selectedGenres,
            year = year,
            mediaType = mediaType,
            sortBy = sortBy,
            voteAverageGte = rating
        )

        viewModel.updateFilters(newFilters)
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
