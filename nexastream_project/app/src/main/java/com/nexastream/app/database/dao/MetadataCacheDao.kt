package com.nexastream.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nexastream.app.models.TmdbMetadataCache

@Dao
interface MetadataCacheDao {
    @Query("SELECT * FROM tmdb_metadata_cache WHERE itemId = :itemId")
    suspend fun getById(itemId: String): TmdbMetadataCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cache: TmdbMetadataCache)

    @Query("DELETE FROM tmdb_metadata_cache WHERE updatedAt < :threshold")
    suspend fun deleteOld(threshold: Long)
}
