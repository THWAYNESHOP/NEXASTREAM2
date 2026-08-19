package com.nexastream.app.adapters.viewholders

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.navigation.findNavController
import com.bumptech.glide.Glide
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemSportMatchMobileBinding
import com.nexastream.app.databinding.ItemSportMatchTvBinding
import com.nexastream.app.fragments.home.HomeMobileFragmentDirections
import com.nexastream.app.fragments.home.HomeTvFragmentDirections
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.Video

class SportMatchViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(_binding.root) {

    private val context = itemView.context
    private lateinit var match: SportMatch

    fun bind(match: SportMatch) {
        this.match = match
        when (_binding) {
            is ItemSportMatchMobileBinding -> displayMobileItem(_binding)
            is ItemSportMatchTvBinding -> displayTvItem(_binding)
        }
    }

    private fun displayMobileItem(binding: ItemSportMatchMobileBinding) {
        binding.apply {
            root.setOnClickListener {
                it.findNavController().navigate(
                    HomeMobileFragmentDirections.actionGlobalPlayer(
                        id = match.id,
                        title = match.title,
                        subtitle = "${match.league} - ${match.time}",
                        videoType = Video.Type.Movie(
                            id = match.id,
                            title = match.title,
                            releaseDate = "",
                            poster = match.poster ?: "",
                            imdbId = null
                        )
                    )
                )
            }
            tvMatchLeague.text = match.league
            tvHomeTeam.text = match.homeTeam
            tvAwayTeam.text = match.awayTeam
            tvMatchScore.text = match.score
            tvMatchTime.text = match.time
            tvMatchStatus.isVisible = match.status == "LIVE"

            Glide.with(context)
                .load(match.poster)
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .into(ivMatchPoster)
        }
    }

    private fun displayTvItem(binding: ItemSportMatchTvBinding) {
        binding.apply {
            root.setOnClickListener {
                it.findNavController().navigate(
                    HomeTvFragmentDirections.actionGlobalPlayer(
                        id = match.id,
                        title = match.title,
                        subtitle = "${match.league} - ${match.time}",
                        videoType = Video.Type.Movie(
                            id = match.id,
                            title = match.title,
                            releaseDate = "",
                            poster = match.poster ?: "",
                            imdbId = null
                        )
                    )
                )
            }
            tvMatchLeague.text = match.league
            tvHomeTeam.text = match.homeTeam
            tvAwayTeam.text = match.awayTeam
            tvMatchScore.text = match.score
            tvMatchTime.text = match.time
            tvMatchStatus.isVisible = match.status == "LIVE"

            Glide.with(context)
                .load(match.poster)
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .into(ivMatchPoster)
        }
    }
}
