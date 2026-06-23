import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Firebase is optional: only apply the google-services plugin once the user drops their
// google-services.json into app/. Without it the Firebase SDK stays dormant and the app runs
// local-only (cloud sync no-ops gracefully). This keeps the project building with no Firebase setup.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Read secrets from local.properties (gitignored) with env-var fallback.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

// Monotonic version so the in-app updater can recognise a newer build. Precedence:
// gradle -PversionCode=N  >  local.properties/env VERSION_CODE  >  baseline. CI passes the run
// number; bump the baseline whenever you cut a manual release.
val appVersionCode: Int = ((findProperty("versionCode") as String?) ?: secret("VERSION_CODE", "2")).toInt()
val appVersionName: String = (findProperty("versionName") as String?) ?: secret("VERSION_NAME", "1.1.0")

android {
    namespace = "com.slickstream"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.slickstream"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // --- App configuration (override in local.properties) ---
        buildConfigField("String", "TMDB_API_KEY", "\"${secret("TMDB_API_KEY")}\"")
        buildConfigField("String", "TMDB_BEARER", "\"${secret("TMDB_BEARER")}\"")
        buildConfigField("String", "TMDB_BASE_URL", "\"https://api.themoviedb.org/3/\"")
        buildConfigField("String", "TMDB_IMAGE_URL", "\"https://image.tmdb.org/t/p/\"")
        // Stremio-compatible indexer (resolves IMDB id -> torrent streams). User-configurable.
        buildConfigField("String", "INDEXER_BASE_URL", "\"${secret("INDEXER_BASE_URL", "https://torrentio.strem.fun/")}\"")
        // Stremio-compatible subtitle addon (resolves IMDB id -> subtitle files). Keyless.
        buildConfigField("String", "SUBTITLE_BASE_URL", "\"${secret("SUBTITLE_BASE_URL", "https://opensubtitles-v3.strem.io/")}\"")
        // Live-sports schedule/stream source (streamed.pk-compatible REST). Keyless, user-overridable.
        buildConfigField("String", "SPORTS_BASE_URL", "\"${secret("SPORTS_BASE_URL", "https://streamed.pk/")}\"")
        // In-app updater: version manifest on a PUBLIC releases repo (a private repo's release
        // assets aren't anonymously downloadable). Override in local.properties.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"${secret("UPDATE_MANIFEST_URL", "https://github.com/jibsta210/slickstream-releases/releases/latest/download/update.json")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${secret("GOOGLE_WEB_CLIENT_ID")}\"")
        // "TVs and Limited Input devices" OAuth client for the Android TV device-pairing flow.
        buildConfigField("String", "GOOGLE_TV_CLIENT_ID", "\"${secret("GOOGLE_TV_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_TV_CLIENT_SECRET", "\"${secret("GOOGLE_TV_CLIENT_SECRET")}\"")
    }

    // Stable release signing — REQUIRED for the in-app updater: an update only installs over the
    // existing app when both APKs share one certificate. Configure a keystore in local.properties
    // (RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD); if
    // absent, release falls back to debug signing so the project still builds.
    signingConfigs {
        val storePath = secret("RELEASE_STORE_FILE")
        if (storePath.isNotBlank() && rootProject.file(storePath).exists()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/previous-compilation-data.bin"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Compose for TV (lazy layouts come from standard compose-foundation; tv-foundation removed)
    implementation(libs.androidx.tv.material)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Images
    implementation(libs.coil.compose)

    // Media playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)

    // Chromecast / Google Cast
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.appcompat)

    // Google auth
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    // Torrent streaming engine
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.libtorrent4j.x86)
    implementation(libs.libtorrent4j.x8664)
    implementation(libs.nanohttpd)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // Firebase — cross-device sync of favourites + watch history (dormant without google-services.json)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
}
