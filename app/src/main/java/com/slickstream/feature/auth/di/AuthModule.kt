package com.slickstream.feature.auth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.slickstream.core.repository.AuthRepository
import com.slickstream.feature.auth.AuthRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Single DataStore instance backing the persisted auth session. */
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "slick_auth")

/** @Binds for [AuthRepository]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}

/** @Provides for the auth DataStore (and the Context the repo needs). */
@Module
@InstallIn(SingletonComponent::class)
object AuthProvideModule {

    @Provides
    @Singleton
    fun provideAuthDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.authDataStore
}
