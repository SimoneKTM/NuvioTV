package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CalendarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val traktApi: TraktApi
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepo"
        private const val TRAKT_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val POSTER_W342 = "${TRAKT_IMAGE_BASE}w342"
        private const val BACKDROP_W780 = "${TRAKT_IMAGE_BASE}w780"
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val daysAhead = 60

        val allItems = mutableListOf<CalendarItem>()

        try {
            val response = traktApi.getCalendarMedia(
                target = "all",
                startDate = todayStr,
                days = daysAhead,
                extended = null
            )

            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                Log.d(TAG, "Trakt calendar media: ${body.size} items")

                for (item in body) {
                    val calendarItem = item.toCalendarItem(today) ?: continue
                    allItems.add(calendarItem)
                }
            } else {
                Log.w(TAG, "Trakt calendar media failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Trakt calendar media", e)
        }

        val filteredItems = allItems
            .filter { it.releaseDate != null && !it.releaseDate.isBefore(today) }
            .distinctBy { it.meta.id }
            .sortedBy { it.releaseDate }

        Log.d(TAG, "Calendar: ${filteredItems.size} items after filtering (${allItems.size} raw)")
        emit(filteredItems)
    }

    private fun com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto.toCalendarItem(
        today: LocalDate
    ): CalendarItem? {
        // Movie item
        val movie = movie
        if (movie != null) {
            val tmdbId = movie.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_movie_$tmdbId" else "trakt_movie_${movie.ids?.trakt ?: 0}"
            val releaseDate = parseDate(released)
            if (releaseDate == null || releaseDate.isBefore(today)) return null

            val posterUrl = movie.images?.poster?.firstOrNull()?.let { url ->
                if (url.startsWith("http")) url else "$POSTER_W342$url"
            }
            val backdropUrl = movie.images?.fanart?.firstOrNull()?.let { url ->
                if (url.startsWith("http")) url else "$BACKDROP_W780$url"
            }

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = movie.title ?: "",
                    poster = posterUrl,
                    posterShape = PosterShape.POSTER,
                    background = backdropUrl,
                    logo = null,
                    description = movie.overview,
                    releaseInfo = movie.year?.toString(),
                    imdbRating = movie.rating?.toFloat(),
                    genres = movie.genres ?: emptyList(),
                    sourceAddonBaseUrl = null
                ),
                releaseDate = releaseDate
            )
        }

        // TV show / episode item
        val show = show
        val episode = episode
        if (show != null) {
            val tmdbId = show.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_tv_$tmdbId" else "trakt_tv_${show.ids?.trakt ?: 0}"
            val airDate = parseDate(firstAired ?: episode?.firstAired)
            if (airDate == null || airDate.isBefore(today)) return null

            val posterUrl = show.images?.poster?.firstOrNull()?.let { url ->
                if (url.startsWith("http")) url else "$POSTER_W342$url"
            }
            val backdropUrl = show.images?.fanart?.firstOrNull()?.let { url ->
                if (url.startsWith("http")) url else "$BACKDROP_W780$url"
            }

            val episodeLabel = if (episode != null) {
                val season = episode.season ?: 0
                val number = episode.number ?: 0
                "S${season}E$number"
            } else null

            val typeLabel = when (episode?.episodeType) {
                "series_premiere" -> "Premiera serie"
                "season_premiere" -> "Premiera stagione"
                "season_finale" -> "Finale stagione"
                "series_finale" -> "Finale serie"
                else -> null
            }

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.SERIES,
                    rawType = "tv",
                    name = show.title ?: "",
                    poster = posterUrl,
                    posterShape = PosterShape.POSTER,
                    background = backdropUrl,
                    logo = null,
                    description = episodeLabel?.let { "$it \u2022 ${show.overview ?: ""}" } ?: show.overview,
                    releaseInfo = show.year?.toString(),
                    imdbRating = show.rating?.toFloat(),
                    genres = show.genres ?: emptyList(),
                    sourceAddonBaseUrl = null
                ),
                releaseDate = airDate
            )
        }

        return null
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr.take(10))
        } catch (e: Exception) {
            null
        }
    }
}
