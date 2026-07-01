package com.slickstream

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.slickstream.data.torrent.TorrentCacheManager
import com.slickstream.data.torrent.TorrentEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SlickStreamApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var torrentEngine: TorrentEngine

    @Inject
    lateinit var torrentCache: TorrentCacheManager

    /** True on RAM-starved TV boxes (the common cheap Android TV) — used to shrink the image cache so a
     *  long session with big backdrops + multi-GB torrents doesn't get OOM-killed mid-playback. */
    private val lowRam: Boolean by lazy {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
    }

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
    // is immutable) aggressively. The cap scales DOWN on low-RAM boxes so images don't crowd out the
    // torrent/player buffers and trigger an OOM-SIGKILL mid-playback.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (lowRam) 0.10 else 0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()

    /**
     * Shed memory under system pressure. Coil auto-trims its own image cache via its ComponentCallbacks,
     * but nothing was reclaiming the on-disk torrent cache except at stream-start — so a long TV session
     * with several multi-GB torrents kept growing until the OS killed the process. On real pressure,
     * evict the torrent cache down to a tight budget (the currently-streaming torrent is always kept).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val budget = if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            512L * 1024 * 1024   // critical: cut hard to ~512 MB
        } else {
            2L * 1024 * 1024 * 1024   // low: trim toward ~2 GB
        }
        runCatching { torrentCache.enforceBudget(maxBytes = budget) }
    }
}
