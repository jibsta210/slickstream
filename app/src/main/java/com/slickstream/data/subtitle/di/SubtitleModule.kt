package com.slickstream.data.subtitle.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.slickstream.core.common.Subtitles
import com.slickstream.data.subtitle.SubtitleApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SubtitleModule {

    @Provides
    @Singleton
    fun provideSubtitleApi(okHttpClient: OkHttpClient, json: Json): SubtitleApi {
        val contentType = "application/json".toMediaType()
        val baseUrl = Subtitles.BASE_URL.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(SubtitleApi::class.java)
    }
}
