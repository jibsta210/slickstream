package com.slickstream.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.ProfileDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.data.local.entity.WatchHistoryEntity

@Database(
    entities = [FavoriteEntity::class, WatchHistoryEntity::class, ProfileEntity::class],
    // v3: multi-profile — favorites/watch_history scoped by profileId + a profiles table.
    // v4: profiles gain an avatarIndex (pickable emoji avatar) — migrated in place (see MIGRATION_3_4)
    // so existing profiles/favourites/history are NOT wiped.
    version = 4,
    exportSchema = false,
)
@TypeConverters(MediaTypeConverter::class)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "slickstream.db"

        /** Add the profiles.avatarIndex column (default 0 = name initial) without wiping data. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN avatarIndex INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
