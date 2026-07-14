package com.slickstream.feature.home

import com.slickstream.core.model.MediaItem
import com.slickstream.core.model.MediaType
import com.slickstream.core.model.PlaybackProgress
import com.slickstream.core.model.WatchHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingSelectionTest {

    @Test
    fun lateFinishedSaveDoesNotBeatRecentlyStartedSuccessor() {
        val episodeOne = row(season = 1, episode = 1, position = 99, duration = 100, updatedAt = 2_000)
        val episodeTwo = row(season = 1, episode = 2, position = 10, duration = 100, updatedAt = 1_900)

        val selected = selectContinueWatchingRow(listOf(episodeOne, episodeTwo))

        assertEquals(2, selected?.progress?.episode)
    }

    @Test
    fun oldSuccessorDoesNotOverrideAnIntentionalRewatch() {
        val now = 10 * 60 * 1_000L
        val rewatchedEpisodeOne = row(
            season = 1,
            episode = 1,
            position = 99,
            duration = 100,
            updatedAt = now,
        )
        val oldEpisodeTwo = row(season = 1, episode = 2, position = 10, duration = 100, updatedAt = 0)

        val selected = selectContinueWatchingRow(listOf(rewatchedEpisodeOne, oldEpisodeTwo))

        assertEquals(1, selected?.progress?.episode)
    }

    @Test
    fun unfinishedNewestEpisodeRemainsAuthoritative() {
        val episodeOne = row(season = 1, episode = 1, position = 50, duration = 100, updatedAt = 2_000)
        val episodeTwo = row(season = 1, episode = 2, position = 10, duration = 100, updatedAt = 1_900)

        val selected = selectContinueWatchingRow(listOf(episodeOne, episodeTwo))

        assertEquals(1, selected?.progress?.episode)
    }

    @Test
    fun normallyNewerSuccessorWinsWithoutRecovery() {
        val episodeOne = row(season = 1, episode = 1, position = 99, duration = 100, updatedAt = 1_900)
        val episodeTwo = row(season = 1, episode = 2, position = 10, duration = 100, updatedAt = 2_000)

        val selected = selectContinueWatchingRow(listOf(episodeOne, episodeTwo))

        assertEquals(2, selected?.progress?.episode)
    }

    @Test
    fun recoveryRecognizesTheFirstEpisodeOfTheNextSeason() {
        val seasonOneFinale = row(
            season = 1,
            episode = 13,
            position = 99,
            duration = 100,
            updatedAt = 2_000,
        )
        val seasonTwoPremiere = row(
            season = 2,
            episode = 1,
            position = 10,
            duration = 100,
            updatedAt = 1_900,
        )

        val selected = selectContinueWatchingRow(listOf(seasonOneFinale, seasonTwoPremiere))

        assertEquals(2, selected?.progress?.season)
        assertEquals(1, selected?.progress?.episode)
    }

    private fun row(
        season: Int,
        episode: Int,
        position: Long,
        duration: Long,
        updatedAt: Long,
    ) = WatchHistoryItem(
        media = show,
        progress = PlaybackProgress(
            mediaId = show.id,
            mediaType = show.mediaType,
            season = season,
            episode = episode,
            positionMs = position,
            durationMs = duration,
            updatedAt = updatedAt,
        ),
    )

    private companion object {
        val show = MediaItem(
            id = 19_152,
            mediaType = MediaType.TV,
            title = "Degrassi Junior High",
            overview = "",
            posterUrl = null,
            backdropUrl = null,
            voteAverage = 0.0,
            releaseDate = null,
        )
    }
}
