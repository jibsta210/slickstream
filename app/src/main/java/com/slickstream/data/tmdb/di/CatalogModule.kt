package com.slickstream.data.tmdb.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.slickstream.core.common.Tmdb
import com.slickstream.core.repository.CatalogRepository
import com.slickstream.data.tmdb.CatalogRepositoryImpl
import com.slickstream.data.tmdb.TmdbApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Binds the catalog repository implementation to its interface.
 *
 * Hilt requires @Binds (abstract) and @Provides to live in separate modules, so the
 * concrete providers live in the [companion object] module below.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CatalogModule {

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository
}

/** Qualifier for the TMDB-specific Retrofit instance (keeps it distinct from any other). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TmdbRetrofit

@Module
@InstallIn(SingletonComponent::class)
object CatalogProvidersModule {

    /**
     * Injects TMDB auth into every request:
     *  - if [Tmdb.BEARER] is set, send `Authorization: Bearer <bearer>`
     *  - otherwise append `api_key=<key>` as a query parameter
     * Also forces JSON responses.
     */
    @Provides
    @Singleton
    @Named("tmdbAuth")
    fun provideTmdbAuthInterceptor(): Interceptor = Interceptor { chain ->
        val original = chain.request()
        val bearer = Tmdb.BEARER
        val builder = original.newBuilder()
            .header("Accept", "application/json")

        if (bearer.isNotBlank()) {
            builder.header("Authorization", "Bearer $bearer")
        } else {
            val url = original.url.newBuilder()
                .addQueryParameter("api_key", Tmdb.API_KEY)
                .build()
            builder.url(url)
        }
        chain.proceed(builder.build())
    }

    @Provides
    @Singleton
    @TmdbRetrofit
    fun provideTmdbRetrofit(
        baseClient: OkHttpClient,
        json: Json,
        @Named("tmdbAuth") authInterceptor: Interceptor,
    ): Retrofit {
        // Reuse the shared client's config (timeouts, logging) and add TMDB auth.
        val client = baseClient.newBuilder()
            .addInterceptor(authInterceptor)
            .build()

        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(Tmdb.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideTmdbApi(@TmdbRetrofit retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)
}
