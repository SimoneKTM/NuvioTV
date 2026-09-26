package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.omdb.OmdbResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmdbAwardsTest {

    @Test
    fun `awards text is returned when response is true`() {
        val dto = OmdbResponseDto(
            response = "True",
            awards = "Won 13 Primetime Emmys. 29 nominations total."
        )
        assertEquals(
            "Won 13 Primetime Emmys. 29 nominations total.",
            OmdbAwardsRepository.parseAwards(dto)
        )
    }

    @Test
    fun `response casing is ignored`() {
        val dto = OmdbResponseDto(response = "true", awards = "Won 4 Oscars.")
        assertEquals("Won 4 Oscars.", OmdbAwardsRepository.parseAwards(dto))
    }

    @Test
    fun `blank awards text is dropped`() {
        val dto = OmdbResponseDto(response = "True", awards = "   ")
        assertNull(OmdbAwardsRepository.parseAwards(dto))
    }

    @Test
    fun `missing awards text is dropped`() {
        val dto = OmdbResponseDto(response = "True", awards = null)
        assertNull(OmdbAwardsRepository.parseAwards(dto))
    }

    @Test
    fun `failed response is dropped even with awards text`() {
        val dto = OmdbResponseDto(response = "False", awards = "Won 1 Oscar.")
        assertNull(OmdbAwardsRepository.parseAwards(dto))
    }

    @Test
    fun `null response body is dropped`() {
        assertNull(OmdbAwardsRepository.parseAwards(null))
    }

    @Test
    fun `imdb title id is extracted from plain id`() {
        assertEquals("tt0903747", OmdbAwardsRepository.extractImdbTitleId("tt0903747"))
    }

    @Test
    fun `imdb title id is extracted from episode style id`() {
        assertEquals("tt0903747", OmdbAwardsRepository.extractImdbTitleId("tt0903747:1:2"))
    }

    @Test
    fun `non imdb id yields no imdb title id`() {
        assertNull(OmdbAwardsRepository.extractImdbTitleId("tmdb:1396"))
        assertNull(OmdbAwardsRepository.extractImdbTitleId(null))
        assertNull(OmdbAwardsRepository.extractImdbTitleId("   "))
    }

    @Test
    fun `tmdb id is extracted only from tmdb prefix`() {
        assertEquals(1396, OmdbAwardsRepository.extractTmdbId("tmdb:1396"))
        assertNull(OmdbAwardsRepository.extractTmdbId("1396"))
        assertNull(OmdbAwardsRepository.extractTmdbId("trakt:1396"))
    }
}
