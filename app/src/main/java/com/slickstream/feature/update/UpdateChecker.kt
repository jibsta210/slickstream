package com.slickstream.feature.update

import android.content.Context
import com.slickstream.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches + parses the hosted update manifest, compares versionCode against the installed build,
 * and streams the APK to external cache with byte-level progress. No-ops gracefully if the
 * manifest URL is unreachable / unset (catch -> null).
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Dismissed version is held IN MEMORY only (not persisted), so a dismissal — including an
     * accidental back-press — lasts for the current process but is forgotten on the next cold start.
     * That gives "update pressure on every reopen" (the user kept losing the prompt because it used
     * to persist forever). The Settings screen also exposes a manual check as a reliable escape hatch.
     */
    @Volatile
    private var dismissedCode = -1

    /** The latest manifest seen this session (for Settings to show "up to date" vs "update available"). */
    @Volatile
    var lastManifest: UpdateManifest? = null
        private set

    /**
     * Returns an Available manifest only if it is newer AND (unless [ignoreDismiss]) not dismissed
     * this session. [ignoreDismiss] is used by the Settings "Check for updates" action so an explicit
     * user check always surfaces an available update.
     */
    suspend fun check(ignoreDismiss: Boolean = false): UpdateManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(BuildConfig.UPDATE_MANIFEST_URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) return@withContext null
                lastManifest = manifest
                if (!ignoreDismiss && !manifest.mandatory && dismissedCode >= manifest.versionCode) {
                    return@withContext null
                }
                manifest
            }
        }.getOrNull()
    }

    fun dismiss(versionCode: Int) {
        dismissedCode = versionCode
    }

    /** Streams the APK to external cache with whole-percent progress callbacks. Returns the file. */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = context.externalCacheDir ?: context.cacheDir
        val out = File(dir, "slickstream-update.apk")
        if (out.exists()) out.delete()
        val req = Request.Builder().url(manifest.apkUrl).build()
        client.newCall(req).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val responseBody = resp.body ?: error("empty body")
            val total = responseBody.contentLength()
            responseBody.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        }
        out
    }
}
