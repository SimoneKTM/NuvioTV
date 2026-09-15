package com.nuvio.tv.ui.screens.calendar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CalendarHomeViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    companion object {
        private const val TAG = "CalendarHomeVM"
    }

    private val _uiState = MutableStateFlow(CalendarHomeUiState())
    val uiState: StateFlow<CalendarHomeUiState> = _uiState.asStateFlow()

    init {
        loadCalendar()
    }

    private fun loadCalendar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                calendarRepository.getCalendarItems().collect { items ->
                    val filtered = filterItems(items, _uiState.value.selectedFilter)
                    val sections = groupItemsByPeriod(filtered)
                    _uiState.update {
                        it.copy(
                            allItems = items,
                            sections = sections,
                            isLoading = false,
                            installedAddonsCount = items.map { item -> item.addonName }.distinct().size
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load calendar", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun filterItems(items: List<CalendarItem>, filter: CalendarFilter): List<CalendarItem> {
        return when (filter) {
            CalendarFilter.ALL -> items
            CalendarFilter.MOVIES -> items.filter { it.meta.type == ContentType.MOVIE }
            CalendarFilter.SERIES -> items.filter { it.meta.type == ContentType.SERIES }
        }
    }

    private fun groupItemsByPeriod(items: List<CalendarItem>): List<CalendarSection> {
        val today = LocalDate.now()
        val sections = mutableListOf<CalendarSection>()

        val thisWeekStart = today
        val thisWeekEnd = today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        val thisWeekItems = items.filter { item ->
            item.releaseDate != null && !item.releaseDate.isBefore(thisWeekStart) && !item.releaseDate.isAfter(thisWeekEnd)
        }
        if (thisWeekItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = "This Week",
                    dateRange = thisWeekStart..thisWeekEnd,
                    items = thisWeekItems.sortedBy { it.releaseDate }
                )
            )
        }

        val nextWeekStart = thisWeekEnd.plusDays(1)
        val nextWeekEnd = nextWeekStart.with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        val nextWeekItems = items.filter { item ->
            item.releaseDate != null && !item.releaseDate.isBefore(nextWeekStart) && !item.releaseDate.isAfter(nextWeekEnd)
        }
        if (nextWeekItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = "Next Week",
                    dateRange = nextWeekStart..nextWeekEnd,
                    items = nextWeekItems.sortedBy { it.releaseDate }
                )
            )
        }

        val laterItems = items.filter { item ->
            item.releaseDate != null && item.releaseDate.isAfter(nextWeekEnd)
        }
        if (laterItems.isNotEmpty()) {
            val groupedByMonth = laterItems.groupBy { item ->
                item.releaseDate!!.withDayOfMonth(1)
            }
            for ((monthStart, monthItems) in groupedByMonth) {
                val monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth())
                val monthLabel = monthStart.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
                sections.add(
                    CalendarSection(
                        label = monthLabel,
                        dateRange = monthStart..monthEnd,
                        items = monthItems.sortedBy { it.releaseDate }
                    )
                )
            }
        }

        val pastItems = items.filter { item ->
            item.releaseDate != null && item.releaseDate.isBefore(today)
        }
        if (pastItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = "Already Released",
                    dateRange = today.minusYears(10)..today.minusDays(1),
                    items = pastItems.sortedByDescending { it.releaseDate }
                )
            )
        }

        return sections
    }

    fun onEvent(event: CalendarHomeEvent) {
        when (event) {
            is CalendarHomeEvent.OnFilterChanged -> {
                _uiState.update { it.copy(selectedFilter = event.filter) }
                val filtered = filterItems(_uiState.value.allItems, event.filter)
                val sections = groupItemsByPeriod(filtered)
                _uiState.update { it.copy(sections = sections) }
            }
            CalendarHomeEvent.OnRetry -> loadCalendar()
        }
    }
}
