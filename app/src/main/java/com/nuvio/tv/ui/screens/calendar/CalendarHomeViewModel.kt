package com.nuvio.tv.ui.screens.calendar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.repository.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private val calendarRepository: CalendarRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "CalendarHomeVM"
    }

    private val _uiState = MutableStateFlow(CalendarHomeUiState())
    val uiState: StateFlow<CalendarHomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadCalendar()
    }

    private fun loadCalendar() {
        // Cancel any in-flight collect so Retry never races a previous load.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasContent = _uiState.value.sections.isNotEmpty()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    // Keep existing sections visible while refreshing.
                    hasLoaded = hasContent || it.hasLoaded
                )
            }
            try {
                calendarRepository.getCalendarItems().collect { items ->
                    val sections = groupItemsByPeriod(items)
                    _uiState.update {
                        it.copy(
                            sections = sections,
                            isLoading = false,
                            error = null,
                            hasLoaded = true
                        )
                    }
                }
                // Flow completed without emission of usable content.
                _uiState.update { state ->
                    if (state.sections.isEmpty() && state.error == null) {
                        state.copy(isLoading = false, hasLoaded = true)
                    } else {
                        state.copy(isLoading = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to load calendar", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private fun groupItemsByPeriod(items: List<CalendarItem>): List<CalendarSection> {
        val today = LocalDate.now()
        val sections = mutableListOf<CalendarSection>()

        // nextOrSame keeps Sunday as the end of "this week" (8-day bug fix).
        val thisWeekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val thisWeekItems = items.filter { item ->
            item.releaseDate != null && !item.releaseDate.isBefore(today) && !item.releaseDate.isAfter(thisWeekEnd)
        }
        if (thisWeekItems.isNotEmpty()) {
            sections.add(
                CalendarSection(
                    label = context.getString(R.string.calendar_this_week),
                    dateRange = today..thisWeekEnd,
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
                    label = context.getString(R.string.calendar_next_week),
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
                val monthLabel = monthStart.format(
                    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                )
                sections.add(
                    CalendarSection(
                        label = monthLabel.replaceFirstChar { it.uppercase(Locale.getDefault()) },
                        dateRange = monthStart..monthEnd,
                        items = monthItems.sortedBy { it.releaseDate }
                    )
                )
            }
        }

        return sections
    }

    fun onEvent(event: CalendarHomeEvent) {
        when (event) {
            CalendarHomeEvent.OnRetry -> loadCalendar()
        }
    }
}
