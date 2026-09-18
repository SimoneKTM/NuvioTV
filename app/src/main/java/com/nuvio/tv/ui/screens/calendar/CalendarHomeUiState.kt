package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.model.CalendarSource

@Immutable
data class CalendarHomeUiState(
    val sections: List<CalendarSection> = emptyList(),
    val allItems: List<CalendarItem> = emptyList(),
    val selectedSource: CalendarSource = CalendarSource.ALL,
    val availableSources: List<CalendarSource> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

sealed class CalendarHomeEvent {
    object OnRetry : CalendarHomeEvent()
    data class OnSourceSelected(val source: CalendarSource) : CalendarHomeEvent()
}
