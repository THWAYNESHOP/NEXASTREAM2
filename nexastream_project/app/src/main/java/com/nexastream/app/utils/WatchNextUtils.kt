package com.nexastream.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.nexastream.app.models.Episode
import com.nexastream.app.models.Movie
import com.nexastream.app.models.WatchItem

@SuppressLint("RestrictedApi")
object WatchNextUtils {

    fun updateWatchNext(context: Context, watchItem: WatchItem) {
        val contentId = when (watchItem) {
            is Movie -> watchItem.id
            is Episode -> watchItem.id
        }

        val existing = getProgram(context, contentId)

        if (watchItem.isWatched) {
            existing?.let { deleteProgramById(context, it.id) }
            return
        }

        val builder = (existing?.let { WatchNextProgram.Builder(it) } ?: WatchNextProgram.Builder())
            .setInternalProviderId(UserPreferences.currentProvider!!.name)
            .setContentId(contentId)
            .setLastEngagementTimeUtcMillis(watchItem.watchHistory?.lastEngagementTimeUtcMillis ?: System.currentTimeMillis())
            .setLastPlaybackPositionMillis(watchItem.watchHistory?.lastPlaybackPositionMillis?.toInt() ?: 0)
            .setDurationMillis(watchItem.watchHistory?.durationMillis?.toInt() ?: 0)

        when (watchItem) {
            is Movie -> {
                builder.setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setTitle(watchItem.title)
                    .setDescription(watchItem.overview)
                    .setPosterArtUri(watchItem.poster?.let { Uri.parse(it) })
            }
            is Episode -> {
                builder.setType(TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                    .setTitle(watchItem.tvShow?.title ?: "")
                    .setSeasonNumber(watchItem.season?.number ?: 0)
                    .setEpisodeNumber(watchItem.number)
                    .setSeasonTitle(watchItem.season?.title)
                    .setEpisodeTitle(watchItem.title)
                    .setDescription("S${watchItem.season?.number} E${watchItem.number} • ${watchItem.title ?: ""}")
                    .setPosterArtUri((watchItem.poster ?: watchItem.tvShow?.poster)?.let { Uri.parse(it) })
            }
        }

        val program = builder.build()

        if (existing != null) {
            updateProgram(context, existing.id, program)
        } else {
            insert(context, program)
        }
    }

    fun programs(context: Context): List<WatchNextProgram> {
        return context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            null,
            null,
            null
        )?.use { cursor ->
            cursor.map { WatchNextProgram.fromCursor(it) }
                .filter { it.internalProviderId == UserPreferences.currentProvider!!.name }
        } ?: listOf()
    }

    fun insert(context: Context, program: WatchNextProgram) {
        context.contentResolver.insert(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            program.toContentValues(),
        )
    }

    fun getProgram(context: Context, contentId: String): WatchNextProgram? {
        return context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            WatchNextProgram.PROJECTION,
            null,
            null,
            null
        )?.use { cursor ->
            cursor.map { WatchNextProgram.fromCursor(it) }
                .find { it.contentId == contentId && it.internalProviderId == UserPreferences.currentProvider!!.name }
        }
    }

    fun updateProgram(context: Context, id: Long, program: WatchNextProgram) {
        context.contentResolver.update(
            TvContractCompat.buildWatchNextProgramUri(id),
            program.toContentValues(),
            null,
            null,
        )
    }

    fun deleteProgramById(context: Context, id: Long) {
        context.contentResolver.delete(
            TvContractCompat.buildWatchNextProgramUri(id),
            null,
            null,
        )
    }
}
