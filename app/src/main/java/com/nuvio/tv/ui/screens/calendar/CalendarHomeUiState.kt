package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection

@Immutable
data class CalendarHomeUiState(
    val sections: List<CalendarSection> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

sealed class CalendarHomeEvent {
    object OnRetry : CalendarHomeEvent()
}
