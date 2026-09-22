package com.nexastream.app.fragments.player.settings

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.databinding.ItemChannelPickerTvBinding
import com.nexastream.app.databinding.ViewPlayerChannelPickerTvBinding
import com.nexastream.app.models.EpgProgram
import com.nexastream.app.models.TvShow
import com.nexastream.app.ui.SpacingItemDecoration
import com.nexastream.app.utils.dp

class PlayerChannelPickerTvView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewPlayerChannelPickerTvBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private var channels: List<TvShow> = emptyList()
    private var programs: Map<String, List<EpgProgram>> = emptyMap()
    private var onChannelSelected: ((TvShow) -> Unit)? = null

    private val adapter = ChannelAdapter()

    init {
        binding.rvChannels.adapter = adapter
        binding.rvChannels.addItemDecoration(SpacingItemDecoration(8.dp(context)))
        visibility = View.GONE
    }

    fun setData(channels: List<TvShow>, programs: List<EpgProgram>) {
        this.channels = channels
        this.programs = programs.groupBy { it.channelId }
        adapter.notifyDataSetChanged()
    }

    fun setOnChannelSelectedListener(listener: (TvShow) -> Unit) {
        this.onChannelSelected = listener
    }

    fun show() {
        isVisible = true
        binding.rvChannels.requestFocus()
    }

    fun hide() {
        isVisible = false
    }

    fun onBackPressed(): Boolean {
        return if (isVisible) {
            hide()
            true
        } else {
            false
        }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            return ChannelViewHolder(
                ItemChannelPickerTvBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val channel = channels[position]
            val now = System.currentTimeMillis()
            val currentProgram = programs[channel.liveMetadata?.channelId]?.find {
                it.startMillis <= now && it.endMillis > now
            }
            holder.bind(channel, currentProgram)
        }

        override fun getItemCount(): Int = channels.size
    }

    private inner class ChannelViewHolder(private val itemBinding: ItemChannelPickerTvBinding) :
        RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(channel: TvShow, currentProgram: EpgProgram?) {
            itemBinding.tvChannelName.text = channel.title
            itemBinding.tvCurrentProgram.text = currentProgram?.title ?: "No information"
            itemBinding.ivFavorite.isVisible = channel.isFavorite
            
            itemBinding.root.setOnClickListener {
                onChannelSelected?.invoke(channel)
                hide()
            }
        }
    }
}
