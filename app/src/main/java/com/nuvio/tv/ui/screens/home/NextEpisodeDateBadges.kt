package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.ui.util.computeUpcomingReleaseBadgeText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

val LocalNextEpisodeDates = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
internal fun rememberNextEpisodeDateLabel(item: MetaPreview): String? {
    val labels = LocalNextEpisodeDates.current
    if (labels.isEmpty()) return null
    return remember(labels, item.id, item.imdbId, item.rawType) {
        nextEpisodeDateKeys(item.id, item.imdbId, item.rawType)
            .firstNotNullOfOrNull { labels[it] }
    }
}

internal fun nextEpisodeDateKeys(id: String?, imdbId: String?, rawType: String?): Set<String> {
    val keys = LinkedHashSet<String>()
    val type = when (rawType?.trim()?.lowercase()) {
        "movie" -> "movie"
        "tv", "series", "show", "anime" -> "tv"
        "channel" -> "channel"
        else -> "unknown"
    }
    imdbId?.trim()?.takeIf { it.isNotEmpty() }?.let { keys += "imdb:$it" }

    val rawId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return keys
    when {
        rawId.startsWith("tt") -> keys += "imdb:$rawId"
        rawId.startsWith("tmdb_tv_") -> keys += "tmdb:tv:${rawId.removePrefix("tmdb_tv_")}"
        rawId.startsWith("tmdb_movie_") -> keys += "tmdb:movie:${rawId.removePrefix("tmdb_movie_")}"
        rawId.startsWith("tmdb:") -> keys += "tmdb:$type:${rawId.substringAfter(':')}"
        rawId.startsWith("trakt_tv_") -> keys += "trakt:tv:${rawId.removePrefix("trakt_tv_")}"
        rawId.startsWith("trakt_movie_") -> keys += "trakt:movie:${rawId.removePrefix("trakt_movie_")}"
        rawId.startsWith("trakt:") -> keys += "trakt:$type:${rawId.substringAfter(':')}"
        rawId.substringBefore(':').toIntOrNull() != null -> {
            keys += "trakt:$type:${rawId.substringBefore(':')}"
            keys += "tmdb:$type:${rawId.substringBefore(':')}"
        }
    }
    keys += "raw:$rawId"
    return keys
}

@HiltViewModel
class NextEpisodeDateBadgesViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _labels = MutableStateFlow<Map<String, String>>(emptyMap())
    val labels: StateFlow<Map<String, String>> = _labels.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                calendarRepository.getCalendarItems().collect { items ->
                    _labels.value = buildNextEpisodeDateLabels(context, items)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("NextEpisodeBadges", "Failed to load calendar labels: ${e.message}")
            }
        }
    }
}

private fun buildNextEpisodeDateLabels(context: Context, items: List<CalendarItem>): Map<String, String> {
    val today = LocalDate.now(ZoneId.systemDefault())
    val labels = HashMap<String, String>()
    for (item in items) {
        val releaseDate = item.releaseDate ?: continue
        if (releaseDate.isBefore(today)) continue
        val label = computeUpcomingReleaseBadgeText(context, releaseDate) ?: continue
        for (key in nextEpisodeDateKeys(item.meta.id, item.meta.imdbId, item.meta.rawType)) {
            labels.putIfAbsent(key, label)
        }
    }
    return labels
}
