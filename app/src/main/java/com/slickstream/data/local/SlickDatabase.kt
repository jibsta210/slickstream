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
    // v5: profiles gain updatedAt (last-write-wins for cross-device profile edits) — MIGRATION_4_5.
    version = 5,
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

        /** Add profiles.updatedAt (default 0) and backfill it to createdAt for existing rows, without
         *  wiping data. updatedAt drives last-write-wins for cross-device profile edits. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE profiles SET updatedAt = createdAt")
            }
        }
    }
}
