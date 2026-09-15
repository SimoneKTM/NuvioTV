package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

@Immutable
data class CalendarSection(
    val label: String,
    val dateRange: ClosedRange<LocalDate>,
    val items: List<CalendarItem>
)

@Immutable
data class CalendarItem(
    val meta: MetaPreview,
    val releaseDate: LocalDate?,
    val addonName: String
)

enum class CalendarFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    SERIES("Series")
}
