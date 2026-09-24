package com.nexastream.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey
    val query: String,
    val poster: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
