package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TmdbApi
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
    private val tmdbApi: TmdbApi,
    private val traktApi: TraktApi
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepository"
        private const val TRAKT_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val POSTER_W342 = "${TRAKT_IMAGE_BASE}w342"
        private const val BACKDROP_W780 = "${TRAKT_IMAGE_BASE}w780"
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val nextMonth = today.plusMonths(1)

        val allItems = mutableListOf<CalendarItem>()

        // Fetch upcoming shows from Trakt Calendar API
        try {
            val showsResponse = traktApi.getCalendarShows(
                startDate = todayStr,
                days = 30
            )
            if (showsResponse.isSuccessful) {
                showsResponse.body()?.forEach { item ->
                    val airDate = item.firstAired?.let { parseTraktDate(it) }
                    val show = item.show
                    val episode = item.episode
                    if (airDate != null && show != null) {
                        val tmdbId = show.ids?.tmdb
                        val posterUrl = show.images?.poster?.firstOrNull()?.let { url ->
                            if (url.startsWith("http")) url else "$POSTER_W342$url"
                        } ?: tmdbId?.let { "$POSTER_W342" } // fallback without path
                        val backdropUrl = show.images?.fanart?.firstOrNull()?.let { url ->
                            if (url.startsWith("http")) url else "$BACKDROP_W780$url"
                        }
                        val id = if (tmdbId != null) "tmdb_tv_$tmdbId" else "trakt_tv_${show.ids?.trakt ?: 0}"
                        val title = show.title ?: ""
                        val episodeLabel = if (episode != null) "S${episode.season ?: 0}E${episode.number ?: 0}" else ""

                        allItems.add(
                            CalendarItem(
                                meta = MetaPreview(
                                    id = id,
                                    type = ContentType.SERIES,
                                    rawType = "tv",
                                    name = title,
                                    poster = posterUrl,
                                    posterShape = PosterShape.POSTER,
                                    background = backdropUrl,
                                    logo = null,
                                    description = episode?.title,
                                    releaseInfo = show.year?.toString(),
                                    imdbRating = show.rating?.toFloat(),
                                    genres = show.genres ?: emptyList(),
                                    sourceAddonBaseUrl = null
                                ),
                                releaseDate = airDate,
                                addonName = "Trakt"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch upcoming shows from Trakt", e)
        }

        // Fetch upcoming movies from TMDB (Trakt calendar/movies returns old films
        // with original release dates instead of actual upcoming releases)
        try {
            val moviesResponse = tmdbApi.discoverMovies(
                apiKey = com.nuvio.tv.BuildConfig.TMDB_API_KEY,
                sortBy = "primary_release_date.asc",
                releaseDateGte = todayStr,
                releaseDateLte = nextMonth.format(DateTimeFormatter.ISO_LOCAL_DATE),
                voteCountGte = 5
            )
            if (moviesResponse.isSuccessful) {
                moviesResponse.body()?.results?.forEach { result ->
                    val releaseDate = parseLocalDate(result.releaseDate)
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

        // Fetch upcoming TV from TMDB as well
        try {
            val tvResponse = tmdbApi.discoverTv(
                apiKey = com.nuvio.tv.BuildConfig.TMDB_API_KEY,
                sortBy = "first_air_date.asc",
                firstAirDateGte = todayStr,
                firstAirDateLte = nextMonth.format(DateTimeFormatter.ISO_LOCAL_DATE),
                voteCountGte = 5
            )
            if (tvResponse.isSuccessful) {
                tvResponse.body()?.results?.forEach { result ->
                    val releaseDate = parseLocalDate(result.firstAirDate)
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

    private fun parseLocalDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr.take(10))
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTraktDate(dateStr: String): LocalDate? {
        return try {
            // Trakt dates look like "2024-01-15T08:00:00.000Z"
            val datePart = dateStr.take(10)
            LocalDate.parse(datePart)
        } catch (e: Exception) {
            null
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
