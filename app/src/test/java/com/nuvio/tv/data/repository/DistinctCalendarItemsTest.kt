package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class DistinctCalendarItemsTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 1)

    private fun meta(id: String, name: String = "Title $id"): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.SERIES,
        rawType = "tv",
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun item(
        id: String,
        date: LocalDate?,
        episodeLabel: String? = null
    ): CalendarItem = CalendarItem(
        meta = meta(id),
        releaseDate = date,
        episodeLabel = episodeLabel
    )

    @Test
    fun sameShowSameDayEpisodesCollapseToSingleCard() {
        val episodes = (1..4).map { episode ->
            item("coven-academy", day, episodeLabel = "S1E$episode")
        }
        val result = distinctCalendarItems(episodes)
        assertEquals(1, result.size)
        assertEquals("S1E1", result.first().episodeLabel)
    }

    @Test
    fun sameShowOnDifferentDaysIsKept() {
        val result = distinctCalendarItems(
            listOf(
                item("show", day, episodeLabel = "S1E1"),
                item("show", day.plusDays(1), episodeLabel = "S1E2")
            )
        )
        assertEquals(2, result.size)
    }

    @Test
    fun differentShowsOnSameDayAreKept() {
        val result = distinctCalendarItems(
            listOf(
                item("show-a", day, episodeLabel = "S1E1"),
                item("show-b", day, episodeLabel = "S2E5")
            )
        )
        assertEquals(2, result.size)
    }

    @Test
    fun duplicateMovieOnSameDayIsCollapsed() {
        val result = distinctCalendarItems(
            listOf(item("tt1234567", day), item("tt1234567", day))
        )
        assertEquals(1, result.size)
    }

    @Test
    fun duplicatesWithoutEpisodeLabelAreCollapsed() {
        val result = distinctCalendarItems(
            listOf(item("show", day), item("show", day))
        )
        assertEquals(1, result.size)
    }

    @Test
    fun firstOccurrenceOrderIsPreserved() {
        val result = distinctCalendarItems(
            listOf(
                item("a", day),
                item("b", day),
                item("a", day)
            )
        )
        assertEquals(listOf("a", "b"), result.map { it.meta.id })
    }

    @Test
    fun nullDateItemsStillDedupeById() {
        val result = distinctCalendarItems(
            listOf(item("show", null), item("show", null), item("other", null))
        )
        assertEquals(2, result.size)
    }
}
