package com.slickstream.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.ProfileDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.data.local.entity.WatchHistoryEntity

@Database(
    entities = [FavoriteEntity::class, WatchHistoryEntity::class, ProfileEntity::class],
    // v3: multi-profile — favorites/watch_history are now scoped by profileId (added to their
    // composite keys) and a profiles table is introduced. Pre-release app, so the LocalModule
    // builder uses fallbackToDestructiveMigration() — local library resets once on upgrade.
    version = 3,
    exportSchema = false,
)
@TypeConverters(MediaTypeConverter::class)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "slickstream.db"
    }
}
