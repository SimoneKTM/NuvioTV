package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestLogoUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val traktApi: TraktApi,
    private val tmdbApi: TmdbApi
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepo"
        private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val POSTER_SIZE = "w780"
        private const val BACKDROP_SIZE = "w1280"
        private const val LOGO_SIZE = "w500"
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
                extended = "fullimages"
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

        val enrichedItems = enrichItemsWithTmdbImages(filteredItems)
        emit(enrichedItems)
    }

    private suspend fun enrichItemsWithTmdbImages(items: List<CalendarItem>): List<CalendarItem> {
        val itemsNeedingImages = items.filter {
            it.meta.poster == null || it.meta.background == null
        }
        if (itemsNeedingImages.isEmpty()) {
            Log.d(TAG, "All items have images, no TMDB enrichment needed")
            return items
        }

        Log.d(TAG, "Enriching ${itemsNeedingImages.size} items with TMDB images")

        val enriched = coroutineScope {
            items.map { item ->
                async {
                    if (item.meta.poster != null && item.meta.background != null) return@async item

                    val tmdbId = extractTmdbId(item.meta.id) ?: return@async item
                    val isMovie = item.meta.type == ContentType.MOVIE

                    try {
                        val details = withContext(Dispatchers.IO) {
                            if (isMovie) {
                                tmdbApi.getMovieDetails(tmdbId, BuildConfig.TMDB_API_KEY).body()
                            } else {
                                tmdbApi.getTvDetails(tmdbId, BuildConfig.TMDB_API_KEY).body()
                            }
                        }

                        val poster = item.meta.poster
                            ?: details?.posterPath?.let { "${TMDB_IMAGE_BASE}${POSTER_SIZE}$it" }
                        val backdrop = item.meta.background
                            ?: details?.backdropPath?.let { "${TMDB_IMAGE_BASE}${BACKDROP_SIZE}$it" }

                        var logo = item.meta.logo
                        if (logo == null) {
                            logo = withContext(Dispatchers.IO) {
                                try {
                                    val imagesResponse = if (isMovie) {
                                        tmdbApi.getMovieImages(tmdbId, BuildConfig.TMDB_API_KEY).body()
                                    } else {
                                        tmdbApi.getTvImages(tmdbId, BuildConfig.TMDB_API_KEY).body()
                                    }
                                    imagesResponse?.logos
                                        ?.firstOrNull { it.iso6391 == "en" || it.iso6391 == null }
                                        ?.filePath
                                        ?.let { "${TMDB_IMAGE_BASE}${LOGO_SIZE}$it" }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }

                        if (poster != item.meta.poster || backdrop != item.meta.background || logo != item.meta.logo) {
                            Log.d(TAG, "Enriched: ${item.meta.name} poster=$poster backdrop=$backdrop logo=$logo")
                        }

                        item.copy(
                            meta = item.meta.copy(
                                poster = poster,
                                background = backdrop,
                                logo = logo
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "TMDB enrichment failed for ${item.meta.name}: ${e.message}")
                        item
                    }
                }
            }.awaitAll()
        }

        return enriched
    }

    private fun extractTmdbId(metaId: String): Int? {
        val tmdbPrefix = "tmdb_movie_"
        val tmdbTvPrefix = "tmdb_tv_"
        val id = when {
            metaId.startsWith(tmdbPrefix) -> metaId.removePrefix(tmdbPrefix)
            metaId.startsWith(tmdbTvPrefix) -> metaId.removePrefix(tmdbTvPrefix)
            else -> return null
        }
        return id.toIntOrNull()
    }

    private fun com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto.toCalendarItem(
        today: LocalDate
    ): CalendarItem? {
        val movie = movie
        if (movie != null) {
            val tmdbId = movie.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_movie_$tmdbId" else "trakt_movie_${movie.ids?.trakt ?: 0}"
            val releaseDate = parseDate(released)
            if (releaseDate == null || releaseDate.isBefore(today)) return null

            val posterUrl = movie.images.traktBestPosterUrl()
            val backdropUrl = movie.images.traktBestBackdropUrl()
            val logoUrl = movie.images.traktBestLogoUrl()

            Log.d(TAG, "Movie: ${movie.title} poster=$posterUrl backdrop=$backdropUrl logo=$logoUrl")

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = movie.title ?: "",
                    poster = posterUrl,
                    posterShape = PosterShape.POSTER,
                    background = backdropUrl,
                    logo = logoUrl,
                    description = movie.overview,
                    releaseInfo = movie.year?.toString(),
                    imdbRating = movie.rating?.toFloat(),
                    genres = movie.genres ?: emptyList(),
                    sourceAddonBaseUrl = null
                ),
                releaseDate = releaseDate
            )
        }

        val show = show
        val episode = episode
        if (show != null) {
            val tmdbId = show.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_tv_$tmdbId" else "trakt_tv_${show.ids?.trakt ?: 0}"
            val airDate = parseDate(firstAired ?: episode?.firstAired)
            if (airDate == null || airDate.isBefore(today)) return null

            val posterUrl = show.images.traktBestPosterUrl()
            val backdropUrl = show.images.traktBestBackdropUrl()
            val logoUrl = show.images.traktBestLogoUrl()

            Log.d(TAG, "Show: ${show.title} poster=$posterUrl backdrop=$backdropUrl logo=$logoUrl")

            val episodeLabel = if (episode != null) {
                val season = episode.season ?: 0
                val number = episode.number ?: 0
                "S${season}E$number"
            } else null

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.SERIES,
                    rawType = "tv",
                    name = show.title ?: "",
                    poster = posterUrl,
                    posterShape = PosterShape.POSTER,
                    background = backdropUrl,
                    logo = logoUrl,
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
