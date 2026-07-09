package com.slickstream.data.local.di

import android.content.Context
import androidx.room.Room
import com.slickstream.core.repository.LibraryRepository
import com.slickstream.core.repository.ProfileRepository
import com.slickstream.data.local.LibraryRepositoryImpl
import com.slickstream.data.local.ProfileRepositoryImpl
import com.slickstream.data.local.SlickDatabase
import com.slickstream.data.local.dao.DownloadDao
import com.slickstream.data.local.dao.FavoriteDao
import com.slickstream.data.local.dao.ProfileDao
import com.slickstream.data.local.dao.WatchHistoryDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the Room database + DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object LocalModule {

    @Provides
    @Singleton
    fun provideSlickDatabase(@ApplicationContext context: Context): SlickDatabase =
        Room.databaseBuilder(context, SlickDatabase::class.java, SlickDatabase.NAME)
            // Preserve profiles/favourites/history across the avatarIndex addition; fall back to a
            // wipe only for version steps without an explicit migration.
            .addMigrations(
                SlickDatabase.MIGRATION_3_4, SlickDatabase.MIGRATION_4_5,
                SlickDatabase.MIGRATION_5_6, SlickDatabase.MIGRATION_6_7,
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideFavoriteDao(db: SlickDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideWatchHistoryDao(db: SlickDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides
    fun provideProfileDao(db: SlickDatabase): ProfileDao = db.profileDao()

    @Provides
    fun provideDownloadDao(db: SlickDatabase): DownloadDao = db.downloadDao()
}

/** Binds the [LibraryRepository] contract to its Room implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalBindsModule {

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository
}
