package com.slickstream.data.source

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the app's set of FREE streaming-addon sources current ALL BY ITSELF — no manual URL lists.
 *
 * On a daily cadence it pulls the community addon catalog, keeps only no-config addons that advertise a
 * "stream" resource, HEALTH-CHECKS each (does it actually return a playable stream for a known title?),
 * and caches the live ones — direct-HTTP (file-server) addons first. [SourceRepositoryImpl] then queries
 * this live set alongside the configured indexer. Dead addons drop out on the next refresh; if the whole
 * catalog is unreachable the last-good cache is kept and torrents remain the always-available fallback.
 *
 * Honest limits: this depends on the community catalog existing, and the set of genuinely-free DIRECT
 * addons fluctuates (some are torrent-backed or need a key — those are auto-excluded). It's best-effort
 * self-maintenance, not a guarantee that a direct stream always exists.
 */
@Singleton
class AddonRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: IndexerApi,
) {
    private val dataStore: DataStore<Preferences> get() = context.addonDataStore

    /** The cached working streaming-addon base URLs (direct-capable first). Empty until first refresh. */
    suspend fun streamingBaseUrls(): List<String> =
        dataStore.data.first()[KEY_URLS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    /** Refresh the working set if the cache is stale (or never built). Safe to call on every launch —
     *  it no-ops within the TTL and swallows all failures (keeping the last-good cache). */
    suspend fun refreshIfStale() {
        val last = dataStore.data.first()[KEY_TS] ?: 0L
        if (System.currentTimeMillis() - last in 0 until TTL_MS) return
        runCatching { refresh() }.onFailure { Log.w(TAG, "addon registry refresh failed", it) }
    }

    private suspend fun refresh() = withContext(Dispatchers.IO) {
        val candidates = api.getAddonCatalog(CATALOG_URL).asSequence()
            .filter { it.transportUrl != null }
            .filter { it.manifest?.behaviorHints?.configurationRequired != true }   // skip key/debrid/config addons
            .filter { hasStreamResource(it) }
            .filter { it.manifest?.types.orEmpty().any { t -> t == "movie" || t == "series" } }
            .mapNotNull { it.transportUrl?.let(::baseOf) }
            .distinct()
            .take(MAX_CANDIDATES)
            .toList()

        val sem = Semaphore(HEALTHCHECK_CONCURRENCY)
        val working = coroutineScope {
            candidates.map { base -> async { sem.withPermit { healthCheck(base) } } }.awaitAll()
        }.filterNotNull()

        // Keep only DIRECT (file-server) addons — torrents already come from the configured indexer, so
        // a torrent-only community addon adds nothing here. This is the "file-server-first" value-add.
        val ordered = working.filter { it.isDirect }.map { it.base }.take(MAX_ACTIVE)
        dataStore.edit {
            it[KEY_URLS] = ordered.joinToString("\n")
            it[KEY_TS] = System.currentTimeMillis()
        }
        Log.i(TAG, "addon registry: ${ordered.size} direct stream addons healthy (of ${candidates.size} checked)")
    }

    /** A working addon = its /stream endpoint returns at least one playable stream (direct url OR a
     *  torrent infoHash) for a universally-available test title, within a short timeout. */
    private suspend fun healthCheck(base: String): WorkingAddon? = withTimeoutOrNull(HEALTHCHECK_TIMEOUT_MS) {
        val resp = runCatching { api.getStreamsAt("${base}stream/movie/$TEST_ID.json") }.getOrNull()
            ?: return@withTimeoutOrNull null
        val usable = resp.streams.filter { !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank() }
        if (usable.isEmpty()) return@withTimeoutOrNull null
        WorkingAddon(base, isDirect = usable.any { !it.url.isNullOrBlank() })
    }

    /** Manifest base from a manifest URL, normalised to end in '/'. */
    private fun baseOf(manifestUrl: String): String =
        manifestUrl.substringBeforeLast("manifest.json").let { if (it.endsWith("/")) it else "$it/" }

    /** True if the manifest advertises the "stream" resource — handling BOTH the string form
     *  (["stream"]) and the object form ([{"name":"stream",...}]). */
    private fun hasStreamResource(entry: com.slickstream.data.source.dto.AddonCatalogEntry): Boolean =
        entry.manifest?.resources.orEmpty().any { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull == "stream"
                is JsonObject -> (el["name"] as? JsonPrimitive)?.contentOrNull == "stream"
                else -> false
            }
        }

    private data class WorkingAddon(val base: String, val isDirect: Boolean)

    private companion object {
        const val TAG = "AddonRegistry"
        const val CATALOG_URL = "https://stremio-addons.com/catalog.json"
        const val TEST_ID = "tt0111161"          // The Shawshank Redemption — present everywhere
        const val TTL_MS = 24L * 60 * 60 * 1000  // refresh at most once a day
        const val MAX_CANDIDATES = 40            // bound the health-check fan-out
        const val HEALTHCHECK_CONCURRENCY = 8
        const val HEALTHCHECK_TIMEOUT_MS = 8_000L
        const val MAX_ACTIVE = 12                // cap the addons queried per title (keeps resolve fast)
        val KEY_URLS = stringPreferencesKey("addon_urls")
        val KEY_TS = longPreferencesKey("addon_refreshed_at")
    }
}

private val Context.addonDataStore: DataStore<Preferences> by preferencesDataStore(name = "slick_addons")
