package com.nexastream.app.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexastream.app.R
import com.nexastream.app.databinding.ItemKeyboardKeyBinding

class SearchKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private val keys = listOf(
        "A", "B", "C", "D", "E", "F",
        "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X",
        "Y", "Z", "1", "2", "3", "4",
        "5", "6", "7", "8", "9", "0",
        ".", "-", "/", "_", "@", "#",
        "(", ")", "?", "!", ":", "+",
        "&", "*", ",", "'", "\"", "$",
        "%", "=", "[", "]", "{", "}"
    )

    var onKeyClickListener: ((String) -> Unit)? = null
    var onBackPressListener: (() -> Boolean)? = null

    init {
        layoutManager = GridLayoutManager(context, 6)
        adapter = KeyboardAdapter(keys) { key ->
            onKeyClickListener?.invoke(key)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (onBackPressListener?.invoke() == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private class KeyboardAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<KeyViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
            return KeyViewHolder(
                ItemKeyboardKeyBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                ),
                onClick
            )
        }

        override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }

    private class KeyViewHolder(
        private val binding: ItemKeyboardKeyBinding,
        private val onClick: (String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(key: String) {
            binding.tvKey.text = key
            binding.root.setOnClickListener { onClick(key) }
        }
    }
}
