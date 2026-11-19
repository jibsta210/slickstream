package com.slickstream.core.catalog

import com.slickstream.core.model.DataResult
import com.slickstream.core.model.MediaItem
import com.slickstream.core.repository.CatalogRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Fetches a page plan of [Shelf]s concurrently and returns de-duplicated rows, in plan order. */
object ShelfLoader {

    data class Result(val rows: List<Pair<Shelf, List<MediaItem>>>, val firstError: String?)

    suspend fun load(
        catalog: CatalogRepository,
        shelves: List<Shelf>,
        today: Shelves.Date,
        salt: Int,
        alreadyShown: Set<String> = emptySet(),
    ): Result = coroutineScope {
        val gate = Semaphore(8)
        val fetched = shelves.map { shelf ->
            async {
                // Two consecutive pages: de-duplication eats the head of later rows, so each shelf
                // needs depth to still fill a row with titles you haven't already scrolled past.
                val page = Shelves.pageFor(shelf, today, salt)
                val pages = listOf(page, page + 1).map { p ->
                    async { gate.withPermit { catalog.getShelf(shelf, p) } }
                }.awaitAll()
                val items = pages.flatMap { (it as? DataResult.Success)?.data.orEmpty() }
                    .distinctBy(Shelves::key)
                Triple(shelf, items, pages.filterIsInstance<DataResult.Error>().firstOrNull()?.message)
            }
        }.awaitAll()
        Result(
            rows = Shelves.dedupe(fetched.map { it.first to it.second }, alreadyShown),
            firstError = fetched.firstNotNullOfOrNull { it.third },
        )
    }
}
