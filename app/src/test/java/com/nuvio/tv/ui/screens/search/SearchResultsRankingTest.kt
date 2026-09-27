package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsRankingTest {

    @Test
    fun `exact title match ranks ahead of partial match`() {
        val exact = preview(id = "exact", name = "Dexter", rating = 6f)
        val partial = preview(id = "partial", name = "Dexter Discussion", rating = 9f)
        val ranked = rankSearchResults("dexter", listOf(partial, exact))
        assertEquals(listOf("exact", "partial"), ranked.map { it.id })
    }

    @Test
    fun `franchise sequels follow original in release order`() {
        val original = preview(id = "dexter", name = "Dexter", year = 2006, rating = 8.6f)
        val sequel = preview(id = "new-blood", name = "Dexter: New Blood", year = 2021, rating = 8.3f)
        val ranked = rankSearchResults("dexter", listOf(sequel, original))
        assertEquals(listOf("dexter", "new-blood"), ranked.map { it.id })
    }

    @Test
    fun `more popular franchise ranks before deep cut with same match quality`() {
        val popular = preview(id = "popular", name = "Breaking Bad", rating = 9.5f)
        val deepCut = preview(id = "deep", name = "Breaking In", rating = 5f)
        val ranked = rankSearchResults("breaking", listOf(deepCut, popular))
        assertEquals(listOf("popular", "deep"), ranked.map { it.id })
    }

    @Test
    fun `match quality dominates popularity`() {
        val exact = preview(id = "exact", name = "Moneyball", rating = 5f)
        val popularPartial = preview(id = "popular", name = "Moneyball Extra", rating = 9.9f)
        val ranked = rankSearchResults("moneyball", listOf(popularPartial, exact))
        assertEquals(listOf("exact", "popular"), ranked.map { it.id })
    }

    @Test
    fun `missing rating sorts after rated items within same family`() {
        val rated = preview(id = "rated", name = "The Wire", rating = 9.3f)
        val unrated = preview(id = "unrated", name = "The Wire Sessions", rating = null)
        val ranked = rankSearchResults("the wire", listOf(unrated, rated))
        assertEquals(listOf("rated", "unrated"), ranked.map { it.id })
    }

    @Test
    fun `single item list is returned unchanged`() {
        val only = preview(id = "only", name = "Heat")
        assertEquals(listOf("only"), rankSearchResults("heat", listOf(only)).map { it.id })
    }

    @Test
    fun `year and season suffixes do not break family grouping`() {
        val root = preview(id = "root", name = "Berlin", year = 2019, rating = 7f)
        val season = preview(id = "season", name = "Berlin Season 2", year = 2023, rating = 8f)
        val ranked = rankSearchResults("berlin", listOf(season, root))
        assertEquals(listOf("root", "season"), ranked.map { it.id })
    }

    @Test
    fun `empty query still groups families and orders by rating`() {
        val high = preview(id = "high", name = "Alpha", rating = 9f)
        val low = preview(id = "low", name = "Beta", rating = 3f)
        val ranked = rankSearchResults("", listOf(low, high))
        assertTrue(ranked.map { it.id }.indexOf("high") < ranked.map { it.id }.indexOf("low"))
    }

    @Test
    fun `seasons sort numerically before alphabetical fallback`() {
        val s1 = preview(id = "s1", name = "Berlin Station Season 1")
        val s2 = preview(id = "s2", name = "Berlin Station Season 2")
        val s10 = preview(id = "s10", name = "Berlin Station Season 10")
        val ranked = rankSearchResults("berlin station", listOf(s10, s1, s2))
        assertEquals(listOf("s1", "s2", "s10"), ranked.map { it.id })
    }

    @Test
    fun `contiguous phrase match ranks above scattered tokens`() {
        val phrase = searchMatchQuality("dark water", "The Dark Water Falls")
        val scattered = searchMatchQuality("dark water", "Water In The Dark")
        assertTrue(
            "expected phrase match ($phrase) before scattered match ($scattered)",
            phrase < scattered
        )
    }

    @Test
    fun `query ending with a season number promotes that season on top`() {
        val s1 = preview(id = "s1", name = "Dark Matter Season 1")
        val s2 = preview(id = "s2", name = "Dark Matter Season 2")
        val s3 = preview(id = "s3", name = "Dark Matter Season 3")
        val ranked = rankSearchResults("dark matter stagione 2", listOf(s3, s1, s2))
        assertEquals(listOf("s2", "s1", "s3"), ranked.map { it.id })
    }

    @Test
    fun `irrelevant season match does not beat relevant title matches`() {
        val s2 = preview(id = "s2", name = "Dark Matter Season 2")
        val s3 = preview(id = "s3", name = "Dark Matter Season 3")
        val other = preview(id = "other", name = "Bright City Season 2")
        val ranked = rankSearchResults("dark matter s2", listOf(other, s3, s2))
        assertEquals(listOf("s2", "s3"), ranked.map { it.id }.take(2))
        assertEquals("other", ranked.last().id)
    }

    @Test
    fun `season number survives a trailing year`() {
        assertEquals(2, searchSeasonNumber(preview(id = "x", name = "Berlin Station Season 2 (2020)")))
        assertEquals(4, searchSeasonNumber(preview(id = "y", name = "Some Show S04")))
    }

    private fun preview(
        id: String,
        name: String,
        rating: Float? = null,
        year: Int? = null
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        name = name,
        poster = "https://img.test/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = year?.toString(),
        imdbRating = rating,
        genres = emptyList(),
        released = year?.let { "$it-01-01" }
    )
}
