package com.nexastream.app.live

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import com.nexastream.app.models.TvShow

object LiveChannelOptionsDialog {
    fun show(context: Context, channel: TvShow) {
        val metadata = channel.liveMetadata ?: return
        val options = buildList {
            add(if (metadata.isFavorite) "Remove from favorites" else "Add to favorites")
            add("Assign to custom group…")
            if (!metadata.customGroup.isNullOrBlank()) add("Remove from ${metadata.customGroup}")
        }
        AlertDialog.Builder(context)
            .setTitle(channel.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        val favorite = !metadata.isFavorite
                        channel.isFavorite = favorite
                        channel.liveMetadata = metadata.copy(isFavorite = favorite)
                        LiveTvRepository.setFavoriteAsync(
                            metadata.channelId,
                            channel.id,
                            channel.title,
                            favorite,
                        )
                        Toast.makeText(
                            context,
                            if (favorite) "Added to favorites" else "Removed from favorites",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    1 -> showGroupEditor(context, channel)
                    else -> {
                        channel.liveMetadata = metadata.copy(customGroup = null)
                        LiveTvRepository.setCustomGroupAsync(metadata.channelId, channel.id, channel.title, null)
                        Toast.makeText(context, "Removed from custom group", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showGroupEditor(context: Context, channel: TvShow) {
        val metadata = channel.liveMetadata ?: return
        val input = EditText(context).apply {
            hint = "Group name"
            setText(metadata.customGroup.orEmpty())
            setSelection(text.length)
            setSingleLine(true)
            setPadding(48, 12, 48, 12)
        }
        AlertDialog.Builder(context)
            .setTitle("Custom group")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val group = input.text.toString().trim().takeIf { it.isNotBlank() }
                channel.liveMetadata = metadata.copy(customGroup = group)
                LiveTvRepository.setCustomGroupAsync(metadata.channelId, channel.id, channel.title, group)
                Toast.makeText(context, "Saved to ${group ?: "no group"}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
