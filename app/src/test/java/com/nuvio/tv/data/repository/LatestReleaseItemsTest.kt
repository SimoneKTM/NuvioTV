package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestReleaseItemsTest {

    private val monthStart: LocalDate = LocalDate.of(2026, 9, 1)
    private val today: LocalDate = LocalDate.of(2026, 9, 20)

    private fun meta(id: String, name: String = "Title $id"): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        rawType = "movie",
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

    private fun item(id: String, date: LocalDate?): CalendarItem =
        CalendarItem(meta = meta(id), releaseDate = date)

    private fun dates(items: List<CalendarItem>): List<LocalDate?> =
        items.map { it.releaseDate }

    @Test
    fun emptyInputsReturnEmptyList() {
        val result = buildLatestReleaseItems(emptyList(), emptyList(), monthStart, today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun itemsBeforeMonthStartAreDropped() {
        val previousMonth = item("prev", LocalDate.of(2026, 8, 30))
        val inMonth = item("in", LocalDate.of(2026, 9, 5))
        val result = buildLatestReleaseItems(
            monthItems = listOf(previousMonth, inMonth),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf("in"), result.map { it.meta.id })
    }

    @Test
    fun pastItemWithinMonthIsKept() {
        val result = buildLatestReleaseItems(
            monthItems = listOf(item("old", LocalDate.of(2026, 9, 5))),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 5)), dates(result))
    }

    @Test
    fun nullDatesAreDropped() {
        val result = buildLatestReleaseItems(
            monthItems = listOf(item("nodate", null), item("dated", LocalDate.of(2026, 9, 12))),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf("dated"), result.map { it.meta.id })
    }

    @Test
    fun seriesWithFutureEpisodeCollapsesToFutureDate() {
        val pastEpisode = item("show", LocalDate.of(2026, 9, 10))
        val futureEpisode = item("show", LocalDate.of(2026, 9, 30))
        val result = buildLatestReleaseItems(
            monthItems = listOf(pastEpisode, futureEpisode),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(1, result.size)
        assertEquals(LocalDate.of(2026, 9, 30), result.first().releaseDate)
    }

    @Test
    fun seriesWithoutFutureOccurrenceKeepsMostRecentPast() {
        val first = item("show", LocalDate.of(2026, 9, 3))
        val second = item("show", LocalDate.of(2026, 9, 10))
        val result = buildLatestReleaseItems(
            monthItems = listOf(first, second),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(1, result.size)
        assertEquals(LocalDate.of(2026, 9, 10), result.first().releaseDate)
    }

    @Test
    fun resultIsChronologicalPastBeforeFuture() {
        val past = item("a", LocalDate.of(2026, 9, 2))
        val future = item("b", LocalDate.of(2026, 9, 28))
        val result = buildLatestReleaseItems(
            monthItems = listOf(past, future),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 28)), dates(result))
    }

    @Test
    fun todayCountsAsFuture() {
        val result = buildLatestReleaseItems(
            monthItems = listOf(item("today", today)),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf(today), dates(result))
        assertTrue(result.first().releaseDate!! >= today)
    }

    @Test
    fun duplicateIdAndDateIsCollapsed() {
        val first = item("dup", LocalDate.of(2026, 9, 12))
        val second = item("dup", LocalDate.of(2026, 9, 12))
        val result = buildLatestReleaseItems(
            monthItems = listOf(first, second),
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = today
        )
        assertEquals(1, result.size)
    }

    @Test
    fun pastBlockKeepsOnlyMostRecentLimit() {
        val pastItems = (1..28).map { day ->
            item("m$day", LocalDate.of(2026, 9, day))
        }
        val result = buildLatestReleaseItems(
            monthItems = pastItems,
            calendarItems = emptyList(),
            monthStart = monthStart,
            today = LocalDate.of(2026, 9, 29)
        )
        assertEquals(LATEST_RELEASE_PAST_LIMIT, result.size)
        assertEquals(LocalDate.of(2026, 9, 4), result.first().releaseDate)
        assertEquals(LocalDate.of(2026, 9, 28), result.last().releaseDate)
    }

    @Test
    fun futureBlockKeepsOnlyNearestLimit() {
        val futureItems = (1..35).map { offset ->
            item("f$offset", today.plusDays(offset.toLong()))
        }
        val result = buildLatestReleaseItems(
            monthItems = emptyList(),
            calendarItems = futureItems,
            monthStart = monthStart,
            today = today
        )
        assertEquals(LATEST_RELEASE_FUTURE_LIMIT, result.size)
        assertEquals(today.plusDays(1), result.first().releaseDate)
        assertEquals(today.plusDays(30), result.last().releaseDate)
    }

    @Test
    fun calendarItemsBeforeMonthStartAreDroppedToo() {
        val stale = item("stale", LocalDate.of(2026, 8, 15))
        val fresh = item("fresh", LocalDate.of(2026, 9, 18))
        val result = buildLatestReleaseItems(
            monthItems = emptyList(),
            calendarItems = listOf(stale, fresh),
            monthStart = monthStart,
            today = today
        )
        assertEquals(listOf("fresh"), result.map { it.meta.id })
    }
}
