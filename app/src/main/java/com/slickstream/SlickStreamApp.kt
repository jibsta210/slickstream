package com.slickstream

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.slickstream.data.torrent.TorrentEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SlickStreamApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var torrentEngine: TorrentEngine

    override fun onCreate() {
        super.onCreate()
        // Warm the libtorrent session + DHT at launch (off-main) so the DHT routing table is already
        // bootstrapped — and the persisted .session_state restored — by the time the user presses
        // Play. Otherwise the first stream pays session.start + DHT bootstrap on the critical path.
        // ensureStarted() is idempotent (AtomicBoolean guard), so this is safe to fire-and-forget.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { torrentEngine.ensureStarted() }
        }
        // When the whole app goes to background, flush libtorrent's DHT/session state so the next
        // cold start finds peers fast even if the process is later killed.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                runCatching { torrentEngine.saveSessionState() }
            }
        })
    }

    // One bounded ImageLoader for the whole app. On TV the default 25%-of-heap cache can be both
    // too big (GC churn) and back oversized ARGB_8888 backdrops; cap it and cache TMDB art (which
    // is immutable) aggressively.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
