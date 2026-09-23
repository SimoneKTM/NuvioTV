package com.nuvio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogUrlBuilderTest {

    @Test
    fun `first page without extras has no skip segment`() {
        assertEquals(
            "https://addon.test/catalog/movie/top.json",
            buildCatalogUrl(
                baseUrl = "https://addon.test",
                type = "movie",
                catalogId = "top",
                skip = 0,
                extraArgs = emptyMap()
            )
        )
    }

    @Test
    fun `skip only appears when greater than zero`() {
        assertEquals(
            "https://addon.test/catalog/movie/top/skip=100.json",
            buildCatalogUrl(
                baseUrl = "https://addon.test",
                type = "movie",
                catalogId = "top",
                skip = 100,
                extraArgs = emptyMap()
            )
        )
    }

    @Test
    fun `search query uses path-style extra with percent-encoded spaces`() {
        assertEquals(
            "https://addon.test/catalog/movie/top/search=batman%20begins.json",
            buildCatalogUrl(
                baseUrl = "https://addon.test",
                type = "movie",
                catalogId = "top",
                skip = 0,
                extraArgs = mapOf("search" to "batman begins")
            )
        )
    }

    @Test
    fun `search plus skip are joined with ampersand in the path segment`() {
        assertEquals(
            "https://addon.test/catalog/series/top/search=batman&skip=50.json",
            buildCatalogUrl(
                baseUrl = "https://addon.test",
                type = "series",
                catalogId = "top",
                skip = 50,
                extraArgs = mapOf("search" to "batman")
            )
        )
    }

    @Test
    fun `base url query string is preserved after the catalog path`() {
        assertEquals(
            "https://addon.test/api/catalog/movie/top/search=x.json?apiKey=abc",
            buildCatalogUrl(
                baseUrl = "https://addon.test/api?apiKey=abc",
                type = "movie",
                catalogId = "top",
                skip = 0,
                extraArgs = mapOf("search" to "x")
            )
        )
    }

    @Test
    fun `trailing slash on base url is normalized`() {
        assertEquals(
            "https://addon.test/catalog/movie/top.json",
            buildCatalogUrl(
                baseUrl = "https://addon.test/",
                type = "movie",
                catalogId = "top",
                skip = 0,
                extraArgs = emptyMap()
            )
        )
    }
}
