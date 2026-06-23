package com.slickstream.feature.sports.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.slickstream.core.common.Sports
import com.slickstream.feature.sports.SportsApi
import com.slickstream.feature.sports.SportsRepository
import com.slickstream.feature.sports.SportsRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * DI wiring for the Live Sports tab. Builds [SportsApi] from the shared OkHttp + Json singletons
 * against [Sports.BASE_URL] (BuildConfig SPORTS_BASE_URL). A blank base URL leaves the repository
 * disabled (the Live tab then shows an unavailable state instead of crashing).
 */
@Module
@InstallIn(SingletonComponent::class)
object SportsModule {

    @Provides
    @Singleton
    fun provideSportsApi(okHttpClient: OkHttpClient, json: Json): SportsApi {
        val contentType = "application/json".toMediaType()
        val base = Sports.BASE_URL.ifBlank { "https://invalid.invalid/" }
            .let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(base)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(SportsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSportsRepository(api: SportsApi): SportsRepository =
        SportsRepositoryImpl(api, Sports.BASE_URL)
}
