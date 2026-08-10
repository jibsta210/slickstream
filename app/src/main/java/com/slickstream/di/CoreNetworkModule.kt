package com.slickstream.di

import com.slickstream.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Shared network singletons. Feature data modules (TMDB, indexer) inject [Json] and
 * [OkHttpClient] and build their own Retrofit instances against their own base URLs.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreNetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    /** Debrid config segments carry an account API token as a path segment; never let one be logged. */
    private val REDACT_DEBRID =
        Regex("(realdebrid|premiumize|alldebrid|debridlink|offcloud|torbox|easydebrid)=[^/\\s]+", RegexOption.IGNORE_CASE)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // BASIC logs the full request LINE, i.e. the whole URL. Since the user's configured source base
        // is "https://torrentio.strem.fun/realdebrid=<TOKEN>/", that wrote a live Real-Debrid credential
        // to logcat on every single title opened — and the APK that reaches the TV is routinely a DEBUG
        // build (slbuild.sh :app:assembleDebug), where logcat is readable by adb and by anything holding
        // READ_LOGS on a sideload-enabled box. Redact any debrid config segment before it is printed,
        // mirroring the masking the settings screen already does on screen.
        val logging = HttpLoggingInterceptor { message ->
            android.util.Log.d("OkHttp", REDACT_DEBRID.replace(message) { "${it.groupValues[1]}=••••••" })
        }.apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
