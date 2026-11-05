package com.slickstream.feature.sports

import com.slickstream.core.model.DataResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live-sports schedule + stream source. Implementations are keyless and best-effort. */
interface SportsRepository {
    /** True when a real source is configured (SPORTS_BASE_URL non-blank). */
    val enabled: Boolean

    suspend fun categories(): DataResult<List<SportCategory>>

    /** Events for a category id, or live events when [categoryId] == [LIVE_CATEGORY_ID]. */
    suspend fun events(categoryId: String): DataResult<List<SportEvent>>

    /** Resolve playable HLS feeds for an event (one or more per source). */
    suspend fun streams(event: SportEvent): DataResult<List<SportStream>>

    companion object {
        const val LIVE_CATEGORY_ID = "__live__"
    }
}

class SportsRepositoryImpl(
    private val api: SportsApi,
    @Suppress("unused") private val fanCode: FanCodeSource,
    @Suppress("unused") private val baseUrl: String,
) : SportsRepository {

    // FanCode is dropped (its feeds are geo-locked to India), and the schedule now comes from ppv.st.
    override val enabled: Boolean get() = true

    /** The whole schedule is one document; categories, a sport's list and an event's feeds all read
     *  it, so it is fetched once and reused briefly instead of three times per tap. */
    private val mutex = Mutex()
    private var cached: PpvScheduleDto? = null
    private var cachedAtMs = 0L

    private suspend fun schedule(): PpvScheduleDto = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAtMs < CACHE_MS }?.let { return@withLock it }
        val fresh = api.schedule(SCHEDULE_URL)
        if (fresh.streams.isEmpty()) error("The live-sports schedule is empty right now.")
        cached = fresh
        cachedAtMs = now
        fresh
    }

    override suspend fun categories(): DataResult<List<SportCategory>> = guard {
        val live = SportCategory(SportsRepository.LIVE_CATEGORY_ID, "Live now")
        // A failed fetch is an ERROR, not an empty list: an empty Success is what made the tab sit
        // there showing nothing when streamed.pk went away.
        (listOf(live) + PpvSchedule.categories(schedule(), System.currentTimeMillis())).distinctBy { it.id }
    }

    override suspend fun events(categoryId: String): DataResult<List<SportEvent>> = guard {
        val now = System.currentTimeMillis()
        if (categoryId == SportsRepository.LIVE_CATEGORY_ID) PpvSchedule.liveEvents(schedule(), now)
        else PpvSchedule.events(schedule(), categoryId, now)
    }

    override suspend fun streams(event: SportEvent): DataResult<List<SportStream>> = guard {
        event.directStream?.let { return@guard listOf(it) }
        PpvSchedule.streamsFor(schedule(), event.id)
    }

    private suspend inline fun <T> guard(crossinline block: suspend () -> T): DataResult<T> =
        withContext(Dispatchers.IO) {
            try {
                DataResult.Success(block())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SportsRepository", "live-sports schedule failed", e)
                DataResult.Error("Couldn't load the live-sports schedule. Check your connection and try again.", e)
            }
        }

    private companion object {
        const val SCHEDULE_URL = "https://api.ppv.st/api/streams"
        const val CACHE_MS = 60_000L
    }
}
