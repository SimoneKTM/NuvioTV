package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.CalendarItem
import java.time.LocalDate

// Caps keep the Home row scrollable: the most recent releases of the
// current month plus the nearest upcoming ones.
internal const val LATEST_RELEASE_PAST_LIMIT = 25
internal const val LATEST_RELEASE_FUTURE_LIMIT = 30

/**
 * Merges the month-start fetch with the regular future calendar fetch into
 * the item list behind the "Latest Releases" Home row:
 *
 * - items before [monthStart] are dropped (they belong to previous months);
 * - duplicates are collapsed per title, preferring the next occurrence on or
 *   after [today] so a series card sits in the upcoming block with its next
 *   episode date, falling back to the most recent past occurrence;
 * - past titles keep only the [LATEST_RELEASE_PAST_LIMIT] most recent ones,
 *   upcoming titles only the [LATEST_RELEASE_FUTURE_LIMIT] nearest ones;
 * - the result is chronological: this month's releases first, then upcoming.
 */
internal fun buildLatestReleaseItems(
    monthItems: List<CalendarItem>,
    calendarItems: List<CalendarItem>,
    monthStart: LocalDate,
    today: LocalDate
): List<CalendarItem> {
    val candidates = (monthItems + calendarItems)
        .filter { item ->
            val date = item.releaseDate ?: return@filter false
            !date.isBefore(monthStart)
        }
        .distinctBy { "${it.meta.id}:${it.releaseDate}" }

    val chosenPerTitle = candidates
        .groupBy { it.meta.id }
        .map { (_, group) ->
            val sorted = group.sortedBy { it.releaseDate }
            sorted.firstOrNull { it.releaseDate?.isBefore(today) == false }
                ?: sorted.last()
        }
        .sortedWith(compareBy({ it.releaseDate }, { it.meta.name }))

    val past = chosenPerTitle
        .filter { (it.releaseDate ?: today).isBefore(today) }
        .takeLast(LATEST_RELEASE_PAST_LIMIT)
    val future = chosenPerTitle
        .filterNot { (it.releaseDate ?: today).isBefore(today) }
        .take(LATEST_RELEASE_FUTURE_LIMIT)

    return past + future
}
