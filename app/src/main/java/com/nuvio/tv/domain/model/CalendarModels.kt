package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

@Immutable
data class CalendarSection(
    val label: String,
    val dateRange: ClosedRange<LocalDate>,
    val items: List<CalendarItem>
)

enum class CalendarSource(val displayName: String) {
    ALL("Tutti"),
    NETFLIX("Netflix"),
    PRIME("Prime Video"),
    DISNEY("Disney+"),
    CINEMA("Cinema"),
    TRAKT("Trakt"),
    TMDB("TMDB")
}

@Immutable
data class CalendarItem(
    val meta: MetaPreview,
    val releaseDate: LocalDate?,
    val addonName: String,
    val source: CalendarSource = CalendarSource.TMDB
)
