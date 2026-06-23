package com.slickstream.data.source.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.slickstream.core.common.Indexer
import com.slickstream.core.repository.SourceRepository
import com.slickstream.data.source.IndexerApi
import com.slickstream.data.source.SourceRepositoryImpl
import dagger.Binds
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
 * DI wiring for the torrent source resolver.
 *
 * Builds the [IndexerApi] Retrofit from the shared [OkHttpClient] + [Json] singletons provided by
 * [com.slickstream.di.CoreNetworkModule] (we never re-provide those). The indexer base URL comes
 * from [Indexer.BASE_URL] (BuildConfig.INDEXER_BASE_URL), which is user-configurable so the app can
 * be pointed at a legal source.
 */
@Module
@InstallIn(SingletonComponent::class)
object SourceModule {

    @Provides
    @Singleton
    fun provideIndexerApi(okHttpClient: OkHttpClient, json: Json): IndexerApi {
        val contentType = "application/json".toMediaType()
        // Indexer.BASE_URL ends with '/'; Retrofit requires a base URL with a trailing slash.
        val baseUrl = Indexer.BASE_URL.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(IndexerApi::class.java)
    }
}

/** Binds the [SourceRepository] interface to its implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourceBindModule {

    @Binds
    @Singleton
    abstract fun bindSourceRepository(impl: SourceRepositoryImpl): SourceRepository
}
