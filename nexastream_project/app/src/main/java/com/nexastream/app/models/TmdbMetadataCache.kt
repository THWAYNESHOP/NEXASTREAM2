package com.nexastream.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tmdb_metadata_cache")
data class TmdbMetadataCache(
    @PrimaryKey val itemId: String, // format: "type|id|lang" or "type|title|year|lang"
    val ageRating: Int?,
    val updatedAt: Long = System.currentTimeMillis()
)
