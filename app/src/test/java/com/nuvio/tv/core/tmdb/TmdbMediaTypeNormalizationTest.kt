package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbMediaTypeNormalizationTest {

    @Test
    fun `standard movie aliases map to movie`() {
        assertEquals("movie", normalizeTmdbMediaType("movie"))
        assertEquals("movie", normalizeTmdbMediaType("FILM"))
    }

    @Test
    fun `standard tv aliases map to tv`() {
        assertEquals("tv", normalizeTmdbMediaType("series"))
        assertEquals("tv", normalizeTmdbMediaType("tv"))
        assertEquals("tv", normalizeTmdbMediaType("show"))
        assertEquals("tv", normalizeTmdbMediaType("tvshow"))
    }

    @Test
    fun `tv-like custom types fold to tv instead of movie`() {
        assertEquals("tv", normalizeTmdbMediaType("anime"))
        assertEquals("tv", normalizeTmdbMediaType("episode"))
        assertEquals("tv", normalizeTmdbMediaType("sport"))
        assertEquals("tv", normalizeTmdbMediaType("live"))
        assertEquals("tv", normalizeTmdbMediaType("channel"))
        assertEquals("tv", normalizeTmdbMediaType(" Anime "))
    }

    @Test
    fun `unknown types pass through lowercased without guessing movie`() {
        assertEquals("other", normalizeTmdbMediaType("Other"))
        assertEquals("", normalizeTmdbMediaType(""))
    }
}
