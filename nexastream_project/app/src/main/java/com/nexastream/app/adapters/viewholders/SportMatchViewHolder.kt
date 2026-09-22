package com.nexastream.app.adapters.viewholders

import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.navigation.findNavController
import com.bumptech.glide.Glide
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemSportMatchMobileBinding
import com.nexastream.app.databinding.ItemSportMatchTvBinding
import com.nexastream.app.models.SportMatch
import com.nexastream.app.models.Video
import com.nexastream.app.utils.loadSportMatchPoster

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
                    R.id.action_global_sports_servers,
                    bundleOf(
                        "id" to match.id,
                        "title" to match.title,
                        "subtitle" to "${match.league} - ${match.time}",
                        "videoType" to Video.Type.Movie(
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

            ivMatchPoster.loadSportMatchPoster(match) {
                placeholder(R.drawable.glide_fallback_cover)
                error(R.drawable.glide_fallback_cover)
            }
        }
    }

    private fun displayTvItem(binding: ItemSportMatchTvBinding) {
        binding.apply {
            val processedMatch = parseLiveDetails(match)
            
            root.setOnClickListener {
                it.findNavController().navigate(
                    R.id.action_global_sports_servers,
                    bundleOf(
                        "id" to processedMatch.id,
                        "title" to processedMatch.title,
                        "subtitle" to "${processedMatch.league} - ${processedMatch.time}",
                        "videoType" to Video.Type.Movie(
                            id = processedMatch.id,
                            title = processedMatch.title,
                            releaseDate = "",
                            poster = processedMatch.poster ?: "",
                            imdbId = null
                        )
                    )
                )
            }
            tvMatchLeague.text = processedMatch.league
            tvHomeTeam.text = processedMatch.homeTeam
            tvAwayTeam.text = processedMatch.awayTeam
            tvMatchScore.text = processedMatch.score
            tvMatchTime.text = processedMatch.time
            tvMatchStatus.text = processedMatch.status
            tvMatchStatus.isVisible = processedMatch.status == "LIVE" || processedMatch.status == "PENS" || processedMatch.status == "HT"

            if (processedMatch.status == "FIN") {
                tvMatchStatus.text = "FINISHED"
                tvMatchStatus.setBackgroundColor(android.graphics.Color.DKGRAY)
                tvMatchStatus.isVisible = true
            } else if (processedMatch.status == "HT") {
                tvMatchStatus.text = "HALF TIME"
                tvMatchStatus.setBackgroundColor(android.graphics.Color.BLUE)
            }

            Glide.with(context)
                .load(processedMatch.poster)
                .placeholder(R.drawable.glide_fallback_cover)
                .error(R.drawable.glide_fallback_cover)
                .into(ivMatchPoster)
        }
    }

    private fun parseLiveDetails(match: SportMatch): SportMatch {
        val title = match.title
        // Simple regex to extract score from title like "Team A 2 - 1 Team B"
        val scoreRegex = Regex("""(\d+)\s*-\s*(\d+)""")
        val matchResult = scoreRegex.find(title)
        
        if (matchResult != null && match.score == "vs") {
            val homeScore = matchResult.groupValues[1]
            val awayScore = matchResult.groupValues[2]
            return match.copy(score = "$homeScore - $awayScore")
        }
        
        return match
    }
}
