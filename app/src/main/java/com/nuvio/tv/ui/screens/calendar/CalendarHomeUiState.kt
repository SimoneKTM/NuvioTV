package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection

@Immutable
data class CalendarHomeUiState(
    val sections: List<CalendarSection> = emptyList(),
    val allItems: List<CalendarItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedFilter: CalendarFilter = CalendarFilter.ALL,
    val installedAddonsCount: Int = 0
)

sealed class CalendarHomeEvent {
    data class OnFilterChanged(val filter: CalendarFilter) : CalendarHomeEvent()
    object OnRetry : CalendarHomeEvent()
}
