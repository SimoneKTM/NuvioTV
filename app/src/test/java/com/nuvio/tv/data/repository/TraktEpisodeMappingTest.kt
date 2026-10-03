package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktEpisodeMappingTest {

    private fun episodes(season: Int, count: Int): List<EpisodeMappingEntry> =
        (1..count).map { EpisodeMappingEntry(season, it, "$it") }

    @Test
    fun `reverse remap maps trakt absolute numbering to addon multi season numbering`() {
        val addon = episodes(1, 24) + episodes(2, 13) + episodes(3, 13) + episodes(4, 5)
        val trakt = episodes(1, 55)

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 51,
            addonEpisodes = addon,
            traktEpisodes = trakt
        )

        assertEquals(EpisodeMappingEntry(4, 1, "1"), mapped)
    }

    @Test
    fun `reverse remap prefers unique title match over index`() {
        val addon = listOf(
            EpisodeMappingEntry(1, 1, "intro"),
            EpisodeMappingEntry(1, 2, "the plan"),
            EpisodeMappingEntry(2, 1, "aftermath")
        )
        val trakt = listOf(
            EpisodeMappingEntry(1, 1, "intro"),
            EpisodeMappingEntry(1, 2, "the plan"),
            EpisodeMappingEntry(1, 3, "aftermath")
        )

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 3,
            requestedTitle = "aftermath",
            addonEpisodes = addon,
            traktEpisodes = trakt
        )

        assertEquals(EpisodeMappingEntry(2, 1, "aftermath"), mapped)
    }

    @Test
    fun `reverse remap returns same entry when both lists are identical`() {
        val list = episodes(1, 10) + episodes(2, 8)

        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 2,
            requestedEpisode = 5,
            addonEpisodes = list,
            traktEpisodes = list
        )

        assertEquals(EpisodeMappingEntry(2, 5, "5"), mapped)
    }

    @Test
    fun `reverse remap returns null when source list is empty`() {
        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 1,
            requestedEpisode = 1,
            addonEpisodes = episodes(1, 4),
            traktEpisodes = emptyList()
        )

        assertNull(mapped)
    }

    @Test
    fun `reverse remap returns null when episode not present in source`() {
        val mapped = reverseRemapEpisodeByTitleOrIndex(
            requestedSeason = 3,
            requestedEpisode = 99,
            addonEpisodes = episodes(1, 4),
            traktEpisodes = episodes(1, 2)
        )

        assertNull(mapped)
    }
}
