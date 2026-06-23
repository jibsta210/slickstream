package com.slickstream.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.WatchHistoryEntity

@Database(
    entities = [FavoriteEntity::class, WatchHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(MediaTypeConverter::class)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        const val NAME = "slickstream.db"
    }
}
