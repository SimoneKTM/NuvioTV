package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.CalendarItem
import kotlinx.coroutines.flow.Flow

interface CalendarRepository {
    fun getCalendarItems(): Flow<List<CalendarItem>>
}
