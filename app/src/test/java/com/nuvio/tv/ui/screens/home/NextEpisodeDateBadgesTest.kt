package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodeDateBadgesTest {

    @Test
    fun `calendar imdb id matches card imdb id`() {
        val calendar = nextEpisodeDateKeys("tt0903747", "tt0903747", "tv")
        val card = nextEpisodeDateKeys("tt0903747", null, "series")
        assertTrue(calendar.intersect(card).isNotEmpty())
    }

    @Test
    fun `calendar imdbId matches card with tmdb id`() {
        val calendar = nextEpisodeDateKeys("tt1396767", "tt1396767", "tv")
        val card = nextEpisodeDateKeys("tmdb_tv_1396", "tt1396767", "series")
        assertTrue(calendar.intersect(card).contains("imdb:tt1396767"))
    }

    @Test
    fun `tmdb prefix id matches colon id across type aliases`() {
        val calendar = nextEpisodeDateKeys("tmdb_tv_1396", null, "tv")
        val card = nextEpisodeDateKeys("tmdb:1396", null, "series")
        assertTrue(calendar.intersect(card).contains("tmdb:tv:1396"))
    }

    @Test
    fun `trakt prefix id matches bare numeric id`() {
        val calendar = nextEpisodeDateKeys("trakt_tv_456", null, "tv")
        val card = nextEpisodeDateKeys("456", null, "series")
        assertTrue(calendar.intersect(card).contains("trakt:tv:456"))
    }

    @Test
    fun `bare numeric movie id does not match tmdb tv calendar id`() {
        val calendar = nextEpisodeDateKeys("tmdb_tv_1396", null, "tv")
        val card = nextEpisodeDateKeys("1396", null, "movie")
        assertEquals(emptySet<String>(), calendar.intersect(card))
    }

    @Test
    fun `exotic id falls back to raw key`() {
        val calendar = nextEpisodeDateKeys("kitsu_42", null, "tv")
        val card = nextEpisodeDateKeys("kitsu_42", null, "anime")
        assertTrue(calendar.intersect(card).contains("raw:kitsu_42"))
    }

    @Test
    fun `different ids do not collide`() {
        val calendar = nextEpisodeDateKeys("tt0903747", "tt0903747", "tv")
        val card = nextEpisodeDateKeys("tt0468569", null, "series")
        assertEquals(emptySet<String>(), calendar.intersect(card))
    }

    @Test
    fun `empty ids produce no keys`() {
        assertEquals(emptySet<String>(), nextEpisodeDateKeys(null, null, "movie"))
        assertEquals(emptySet<String>(), nextEpisodeDateKeys("", "  ", "movie"))
    }
}
