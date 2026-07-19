package com.slickstream.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.slickstream.data.local.dao.DownloadDao
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.ProfileDao
import com.slickstream.data.local.dao.WatchHistoryDao
import com.slickstream.data.local.entity.DownloadEntity
import com.slickstream.data.local.entity.FavoriteEntity
import com.slickstream.data.local.entity.ProfileEntity
import com.slickstream.data.local.entity.WatchHistoryEntity

@Database(
    entities = [
        FavoriteEntity::class, WatchHistoryEntity::class, ProfileEntity::class, DownloadEntity::class,
        com.slickstream.data.local.entity.SourceStatusEntity::class,
    ],
    // v3: multi-profile — favorites/watch_history scoped by profileId + a profiles table.
    // v4: profiles gain an avatarIndex (pickable emoji avatar) — migrated in place (see MIGRATION_3_4)
    // so existing profiles/favourites/history are NOT wiped.
    // v5: profiles gain updatedAt (last-write-wins for cross-device profile edits) — MIGRATION_4_5.
    // v6: a downloads table (offline downloads) — MIGRATION_5_6 (additive CREATE TABLE).
    // v7: downloads.fileIndex — which file inside a season-pack torrent, so a resumed pack episode
    //     re-selects the RIGHT file instead of the largest — MIGRATION_6_7 (additive column).
    // v8: source_status table — cached per-title source availability so the catalog stops headlining
    //     titles with nothing to play — MIGRATION_7_8 (additive CREATE TABLE).
    version = 8,
    exportSchema = false,
)
@TypeConverters(MediaTypeConverter::class)
abstract class SlickDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun profileDao(): ProfileDao
    abstract fun downloadDao(): DownloadDao
    abstract fun sourceStatusDao(): com.slickstream.data.local.dao.SourceStatusDao

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

        /** Add the downloads table (additive) without wiping favourites/history/profiles. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS downloads (" +
                        "mediaId INTEGER NOT NULL, mediaType TEXT NOT NULL, season INTEGER NOT NULL, " +
                        "episode INTEGER NOT NULL, title TEXT NOT NULL, subtitle TEXT NOT NULL, " +
                        "posterUrl TEXT, backdropUrl TEXT, overview TEXT NOT NULL, quality TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, infoHash TEXT, magnetUri TEXT, directUrl TEXT, " +
                        "filePath TEXT, status TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, " +
                        "totalBytes INTEGER NOT NULL, addedAt INTEGER NOT NULL, profileId TEXT NOT NULL, " +
                        "PRIMARY KEY(mediaId, mediaType, season, episode))",
                )
            }
        }

        /** Add downloads.fileIndex (nullable) so a resumed season-pack episode re-selects the exact
         *  file instead of falling back to the largest one. Additive; existing rows get NULL. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN fileIndex INTEGER")
            }
        }

        /** Add the source_status availability cache (additive). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_status (" +
                        "mediaId INTEGER NOT NULL, mediaType TEXT NOT NULL, " +
                        "hasSources INTEGER NOT NULL, checkedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(mediaId, mediaType))",
                )
            }
        }
    }
}
