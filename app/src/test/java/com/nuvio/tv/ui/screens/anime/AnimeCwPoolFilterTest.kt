package com.nuvio.tv.ui.screens.anime

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeCwPoolFilterTest {
    @Test
    fun `pool marker passes the anime filter without addon url`() {
        val progress = progress(pool = WatchProgress.POOL_ANIME)

        assertTrue(isAnimeProgress(progress, emptySet()))
    }

    @Test
    fun `simkl anime source url passes the anime filter`() {
        val progress = progress(trackingSourceUrl = "https://simkl.com/anime/51283/one-piece")

        assertTrue(isAnimeProgress(progress, emptySet()))
    }

    @Test
    fun `simkl non-anime source url does not pass the anime filter`() {
        val progress = progress(trackingSourceUrl = "https://simkl.com/tv/12345/slug")

        assertFalse(isAnimeProgress(progress, setOf("https://anime.example")))
    }

    @Test
    fun `addon base url inside the anime pool passes the filter`() {
        val progress = progress(addonBaseUrl = "https://anime.example/")

        assertTrue(isAnimeProgress(progress, setOf("https://anime.example")))
    }

    @Test
    fun `addon base url outside the anime pool fails the filter`() {
        val progress = progress(addonBaseUrl = "https://movies.example")

        assertFalse(isAnimeProgress(progress, setOf("https://anime.example")))
    }

    @Test
    fun `remote progress without any anime signal fails the filter`() {
        val progress = progress()

        assertFalse(isAnimeProgress(progress, setOf("https://anime.example")))
    }

    private fun progress(
        addonBaseUrl: String? = null,
        pool: String? = null,
        trackingSourceUrl: String? = null
    ) = WatchProgress(
        contentId = "tt1234567",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt1234567:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = 1_000L,
        duration = 10_000L,
        lastWatched = 1L,
        addonBaseUrl = addonBaseUrl,
        pool = pool,
        trackingSourceUrl = trackingSourceUrl
    )
}
