package com.nexastream.app.models

import com.nexastream.app.adapters.AppAdapter

class Genre(
    val id: String,
    val name: String,

    val shows: List<AppAdapter.Item> = listOf(),
) : AppAdapter.Item {

    override lateinit var itemType: AppAdapter.Type
    override var isSelected: Boolean = false


    fun copy(
        id: String = this.id,
        name: String = this.name,
        shows: List<AppAdapter.Item> = this.shows,
    ) = Genre(
        id,
        name,
        shows
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Genre

        if (id != other.id) return false
        if (name != other.name) return false
        if (shows != other.shows) return false
        if (isSelected != other.isSelected) return false
        if (!::itemType.isInitialized || !other::itemType.isInitialized) return false
        return itemType == other.itemType
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + shows.hashCode()
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + (if (::itemType.isInitialized) itemType.hashCode() else 0)
        return result
    }
}
