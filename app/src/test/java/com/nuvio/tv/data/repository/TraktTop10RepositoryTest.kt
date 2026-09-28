package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktTop10RepositoryTest {

    private fun showDto(
        title: String? = "Show",
        ids: TraktIdsDto? = null,
        year: Int? = null,
        rating: Double? = null,
        genres: List<String>? = null,
        overview: String? = null,
        images: com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto? = null
    ) = TraktShowDto(
        title = title,
        year = year,
        ids = ids,
        overview = overview,
        rating = rating,
        genres = genres,
        images = images
    )

    @Test
    fun `maps show fields into a series meta preview`() {
        val previews = mapTopShowPreviews(
            listOf(
                TraktWatchedShowItemDto(
                    plays = 42,
                    show = showDto(
                        title = "Severance",
                        ids = TraktIdsDto(
                            trakt = 1,
                            slug = "severance",
                            imdb = "tt11280740",
                            tmdb = 95396
                        ),
                        year = 2022,
                        rating = 8.7,
                        genres = listOf("Drama", "Mystery"),
                        overview = "Office workers trapped forever."
                    )
                )
            )
        )

        assertEquals(1, previews.size)
        val preview = previews.first()
        assertEquals("tt11280740", preview.id)
        assertEquals(ContentType.SERIES, preview.type)
        assertEquals("series", preview.apiType)
        assertEquals("Severance", preview.name)
        assertEquals(PosterShape.POSTER, preview.posterShape)
        assertEquals("2022", preview.releaseInfo)
        assertEquals(8.7f, preview.imdbRating!!, 0.001f)
        assertEquals(listOf("Drama", "Mystery"), preview.genres)
        assertEquals("tt11280740", preview.imdbId)
        assertEquals("severance", preview.slug)
        assertEquals("Office workers trapped forever.", preview.description)
    }

    @Test
    fun `takes only the first ten entries keeping trakt rank order`() {
        val dtos = (1..15).map { n ->
            TraktWatchedShowItemDto(show = showDto(title = "Show $n"))
        }

        val previews = mapTopShowPreviews(dtos)

        assertEquals(10, previews.size)
        assertEquals(
            (1..10).map { "Show $it" },
            previews.map { it.name }
        )
    }

    @Test
    fun `entries without a show object are skipped without shifting ranks`() {
        val previews = mapTopShowPreviews(
            listOf(
                TraktWatchedShowItemDto(show = null),
                TraktWatchedShowItemDto(show = showDto(title = "First valid")),
                TraktWatchedShowItemDto(show = null),
                TraktWatchedShowItemDto(show = showDto(title = "Second valid"))
            )
        )

        assertEquals(listOf("First valid", "Second valid"), previews.map { it.name })
    }

    @Test
    fun `content id prefers imdb then tmdb then trakt`() {
        val previews = mapTopShowPreviews(
            listOf(
                TraktWatchedShowItemDto(
                    show = showDto(ids = TraktIdsDto(trakt = 1, tmdb = 10, imdb = "tt1"))
                ),
                TraktWatchedShowItemDto(
                    show = showDto(ids = TraktIdsDto(trakt = 2, tmdb = 20))
                ),
                TraktWatchedShowItemDto(
                    show = showDto(ids = TraktIdsDto(trakt = 3))
                ),
                TraktWatchedShowItemDto(
                    show = showDto(ids = TraktIdsDto(trakt = null))
                )
            )
        )

        assertEquals("tt1", previews[0].id)
        assertEquals("tmdb_tv_20", previews[1].id)
        assertEquals("trakt_tv_3", previews[2].id)
        assertEquals("trakt_tv_0", previews[3].id)
    }

    @Test
    fun `blank description is stored as null`() {
        val previews = mapTopShowPreviews(
            listOf(
                TraktWatchedShowItemDto(show = showDto(overview = "   ")),
                TraktWatchedShowItemDto(show = showDto(overview = null))
            )
        )

        assertNull(previews[0].description)
        assertNull(previews[1].description)
    }

    @Test
    fun `shows without a title map to an empty name instead of being dropped`() {
        val previews = mapTopShowPreviews(
            listOf(TraktWatchedShowItemDto(show = showDto(title = null)))
        )

        assertEquals(1, previews.size)
        assertEquals("", previews.first().name)
    }

    @Test
    fun `trakt poster images are normalized to https urls`() {
        val previews = mapTopShowPreviews(
            listOf(
                TraktWatchedShowItemDto(
                    show = showDto(
                        images = com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto(
                            poster = listOf("https://x.com/p.jpg"),
                            fanart = listOf("//images.fanart")
                        )
                    )
                ),
                TraktWatchedShowItemDto(
                    show = showDto(
                        images = com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto(
                            fanart = listOf("//images.fanart")
                        )
                    )
                )
            )
        )

        assertEquals("https://x.com/p.jpg", previews[0].poster)
        assertEquals("https://images.fanart", previews[1].poster)
        assertEquals("https://images.fanart", previews[0].background)
        assertNotNull(previews[0].poster)
        assertTrue(previews[1].background!!.startsWith("https://"))
    }
}
