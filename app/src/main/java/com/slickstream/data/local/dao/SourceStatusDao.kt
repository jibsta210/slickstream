package com.slickstream.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.slickstream.data.local.entity.SourceStatusEntity

@Dao
interface SourceStatusDao {

    @Query("SELECT * FROM source_status")
    suspend fun getAll(): List<SourceStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceStatusEntity)
}
