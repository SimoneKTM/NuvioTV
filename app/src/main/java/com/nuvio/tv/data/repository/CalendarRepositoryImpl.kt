package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.data.remote.api.TmdbApi
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
    private val tmdbApi: TmdbApi
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepository"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val POSTER_W342 = "${TMDB_IMAGE_BASE}w342"
        private const val BACKDROP_W780 = "${TMDB_IMAGE_BASE}w780"
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nextMonth = today.plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val apiKey = BuildConfig.TMDB_API_KEY

        val allItems = mutableListOf<CalendarItem>()

        try {
            val moviesResponse = tmdbApi.discoverMovies(
                apiKey = apiKey,
                sortBy = "primary_release_date.asc",
                releaseDateGte = todayStr,
                releaseDateLte = nextMonth,
                voteCountGte = 10
            )
            if (moviesResponse.isSuccessful) {
                moviesResponse.body()?.results?.forEach { result ->
                    val releaseDate = result.releaseDate?.let { parseDate(it) }
                    if (releaseDate != null) {
                        allItems.add(
                            CalendarItem(
                                meta = result.toMetaPreview("movie"),
                                releaseDate = releaseDate,
                                addonName = "TMDB"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch upcoming movies from TMDB", e)
        }

        try {
            val tvResponse = tmdbApi.discoverTv(
                apiKey = apiKey,
                sortBy = "first_air_date.asc",
                firstAirDateGte = todayStr,
                firstAirDateLte = nextMonth,
                voteCountGte = 10
            )
            if (tvResponse.isSuccessful) {
                tvResponse.body()?.results?.forEach { result ->
                    val releaseDate = result.firstAirDate?.let { parseDate(it) }
                    if (releaseDate != null) {
                        allItems.add(
                            CalendarItem(
                                meta = result.toMetaPreview("tv"),
                                releaseDate = releaseDate,
                                addonName = "TMDB"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch upcoming TV from TMDB", e)
        }

        emit(allItems)
    }

    private fun parseDate(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr)
        } catch (e: Exception) {
            parseEpisodeReleaseLocalDate(dateStr)
        }
    }

    private fun com.nuvio.tv.data.remote.api.TmdbDiscoverResult.toMetaPreview(
        mediaType: String
    ): MetaPreview {
        val tmdbId = this.id
        val id = "tmdb_${mediaType}_${tmdbId}"
        val title = title ?: name ?: ""
        val posterUrl = posterPath?.let { "$POSTER_W342$it" }
        val backdropUrl = backdropPath?.let { "$BACKDROP_W780$it" }
        val contentType = if (mediaType == "movie") ContentType.MOVIE else ContentType.SERIES

        return MetaPreview(
            id = id,
            type = contentType,
            rawType = mediaType,
            name = title,
            poster = posterUrl,
            posterShape = PosterShape.POSTER,
            background = backdropUrl,
            logo = null,
            description = overview,
            releaseInfo = releaseDate ?: firstAirDate,
            imdbRating = voteAverage?.toFloat(),
            genres = emptyList(),
            sourceAddonBaseUrl = null
        )
    }
}
