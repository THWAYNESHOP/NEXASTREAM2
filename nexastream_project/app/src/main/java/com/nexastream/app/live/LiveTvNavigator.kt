package com.nexastream.app.live

import android.os.Bundle
import androidx.navigation.NavController
import com.nexastream.app.R
import com.nexastream.app.models.TvShow
import com.nexastream.app.models.Video

object LiveTvNavigator {
    fun playChannel(navController: NavController, channel: TvShow) {
        channel.liveMetadata?.channelId?.let { channelId ->
            LiveTvRepository.markChannelWatched(channelId, channel.id, channel.title)
        }
        val videoType = Video.Type.Episode(
            id = channel.id,
            number = 1,
            title = channel.title,
            poster = channel.poster,
            overview = channel.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = channel.id,
                title = channel.title,
                poster = channel.poster,
                banner = channel.banner,
                releaseDate = null,
                imdbId = null,
            ),
            season = Video.Type.Episode.Season(number = 1, title = "Live"),
        )
        navController.navigate(
            R.id.player,
            Bundle().apply {
                putString("id", channel.id)
                putString("title", channel.title)
                putString("subtitle", channel.liveMetadata?.nowNext?.now?.title ?: "Live")
                putSerializable("videoType", videoType)
            },
        )
    }

    fun playRecording(navController: NavController, recordingId: String, title: String) {
        val id = LiveTvCodec.RECORDING_PREFIX + recordingId
        navController.navigate(
            R.id.player,
            Bundle().apply {
                putString("id", id)
                putString("title", title)
                putString("subtitle", "DVR recording")
                putSerializable(
                    "videoType",
                    Video.Type.Movie(
                        id = id,
                        title = title,
                        releaseDate = "",
                        poster = "",
                        imdbId = null,
                    ),
                )
            },
        )
    }
}
