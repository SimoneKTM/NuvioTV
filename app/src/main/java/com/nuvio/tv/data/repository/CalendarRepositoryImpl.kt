package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CalendarRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val traktApi: TraktApi,
    private val okHttpClient: OkHttpClient
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepository"
        private const val TRAKT_IMAGE_BASE = "https://image.tmdb.org/t/p/"
        private const val POSTER_W342 = "${TRAKT_IMAGE_BASE}w342"
        private const val BACKDROP_W780 = "${TRAKT_IMAGE_BASE}w780"

        private const val NETFLIX_PROVIDER_ID = 8
        private const val PRIME_PROVIDER_ID = 9
        private const val DISNEY_PROVIDER_ID = 337
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val twoMonthsLater = today.plusMonths(2)
        val twoMonthsLaterStr = twoMonthsLater.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val tmdbApiKey = com.nuvio.tv.BuildConfig.TMDB_API_KEY

        val allItems = mutableListOf<CalendarItem>()
        val sourceErrors = mutableListOf<String>()

        coroutineScope {
            // 1. Trakt Calendar (TV episodes)
            val traktDeferred = async(Dispatchers.IO) {
                fetchTraktCalendar(today, todayStr)
            }

            // 2. TMDB - Netflix Movies & TV
            val netflixMoviesDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "movie", NETFLIX_PROVIDER_ID, "Netflix", todayStr, twoMonthsLaterStr) else emptyList()
            }
            val netflixTvDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "tv", NETFLIX_PROVIDER_ID, "Netflix", todayStr, twoMonthsLaterStr) else emptyList()
            }

            // 3. TMDB - Prime Video Movies & TV
            val primeMoviesDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "movie", PRIME_PROVIDER_ID, "Prime Video", todayStr, twoMonthsLaterStr) else emptyList()
            }
            val primeTvDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "tv", PRIME_PROVIDER_ID, "Prime Video", todayStr, twoMonthsLaterStr) else emptyList()
            }

            // 4. TMDB - Disney+ Movies & TV
            val disneyMoviesDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "movie", DISNEY_PROVIDER_ID, "Disney+", todayStr, twoMonthsLaterStr) else emptyList()
            }
            val disneyTvDeferred = async(Dispatchers.IO) {
                if (tmdbApiKey.isNotBlank()) fetchTmdbProvider(tmdbApiKey, "tv", DISNEY_PROVIDER_ID, "Disney+", todayStr, twoMonthsLaterStr) else emptyList()
            }

            // 5. ComingSoon.it RSS (Italian cinema)
            val cinemaDeferred = async(Dispatchers.IO) {
                fetchComingSoonRss(today)
            }

            // Collect all results
            try { allItems.addAll(traktDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Trakt failed", e)
                sourceErrors.add("Trakt: ${e.localizedMessage}")
            }
            try { allItems.addAll(netflixMoviesDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Netflix movies failed", e)
                sourceErrors.add("Netflix Film: ${e.localizedMessage}")
            }
            try { allItems.addAll(netflixTvDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Netflix TV failed", e)
                sourceErrors.add("Netflix TV: ${e.localizedMessage}")
            }
            try { allItems.addAll(primeMoviesDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Prime movies failed", e)
                sourceErrors.add("Prime Film: ${e.localizedMessage}")
            }
            try { allItems.addAll(primeTvDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Prime TV failed", e)
                sourceErrors.add("Prime TV: ${e.localizedMessage}")
            }
            try { allItems.addAll(disneyMoviesDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Disney movies failed", e)
                sourceErrors.add("Disney+ Film: ${e.localizedMessage}")
            }
            try { allItems.addAll(disneyTvDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "Disney TV failed", e)
                sourceErrors.add("Disney+ TV: ${e.localizedMessage}")
            }
            try { allItems.addAll(cinemaDeferred.await()) } catch (e: Exception) {
                Log.e(TAG, "ComingSoon failed", e)
                sourceErrors.add("Cinema: ${e.localizedMessage}")
            }
        }

        if (tmdbApiKey.isBlank()) {
            sourceErrors.add("TMDB: API key non configurata")
        }

        val filteredItems = allItems.filter { item ->
            val releaseDate = item.releaseDate ?: return@filter false
            !releaseDate.isBefore(today)
        }.distinctBy { "${it.meta.id}_${it.source}" }

        if (filteredItems.isEmpty() && sourceErrors.isNotEmpty()) {
            throw CalendarLoadException(
                "Fonti dati non disponibili:\n${sourceErrors.joinToString("\n")}"
            )
        }

        emit(filteredItems)
    }

    class CalendarLoadException(message: String) : Exception(message)

    private suspend fun fetchTraktCalendar(today: LocalDate, todayStr: String): List<CalendarItem> {
        val items = mutableListOf<CalendarItem>()
        try {
            val showsResponse = traktApi.getCalendarShows(startDate = todayStr, days = 60)
            if (showsResponse.isSuccessful) {
                showsResponse.body()?.forEach { item ->
                    val airDate = item.firstAired?.let { parseTraktDate(it) }
                    val show = item.show
                    val episode = item.episode
                    if (airDate != null && show != null && !airDate.isBefore(today)) {
                        val tmdbId = show.ids?.tmdb
                        val posterUrl = show.images?.poster?.firstOrNull()?.let { url ->
                            if (url.startsWith("http")) url else "$POSTER_W342$url"
                        } ?: tmdbId?.let { "$POSTER_W342" }
                        val backdropUrl = show.images?.fanart?.firstOrNull()?.let { url ->
                            if (url.startsWith("http")) url else "$BACKDROP_W780$url"
                        }
                        val id = if (tmdbId != null) "tmdb_tv_$tmdbId" else "trakt_tv_${show.ids?.trakt ?: 0}"
                        val title = show.title ?: ""
                        val episodeLabel = if (episode != null) "S${episode.season ?: 0}E${episode.number ?: 0}" else ""

                        items.add(
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
                                    description = episode?.let { ep -> episodeLabel },
                                    releaseInfo = show.year?.toString(),
                                    imdbRating = show.rating?.toFloat(),
                                    genres = show.genres ?: emptyList(),
                                    sourceAddonBaseUrl = null
                                ),
                                releaseDate = airDate,
                                addonName = "Trakt",
                                source = CalendarSource.TRAKT
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trakt calendar failed", e)
            throw e
        }
        return items
    }

    private suspend fun fetchTmdbProvider(
        apiKey: String,
        mediaType: String,
        providerId: Int,
        providerName: String,
        dateFrom: String,
        dateTo: String
    ): List<CalendarItem> {
        val items = mutableListOf<CalendarItem>()
        try {
            if (mediaType == "movie") {
                val response = tmdbApi.discoverMovies(
                    apiKey = apiKey,
                    sortBy = "primary_release_date.asc",
                    withWatchProviders = providerId.toString(),
                    watchRegion = "IT",
                    releaseDateGte = dateFrom,
                    releaseDateLte = dateTo
                )
                if (response.isSuccessful) {
                    response.body()?.results?.forEach { result ->
                        val releaseDate = parseLocalDate(result.releaseDate)
                        if (releaseDate != null && !releaseDate.isBefore(LocalDate.now())) {
                            items.add(
                                CalendarItem(
                                    meta = result.toMetaPreview("movie"),
                                    releaseDate = releaseDate,
                                    addonName = providerName,
                                    source = when (providerId) {
                                        NETFLIX_PROVIDER_ID -> CalendarSource.NETFLIX
                                        PRIME_PROVIDER_ID -> CalendarSource.PRIME
                                        DISNEY_PROVIDER_ID -> CalendarSource.DISNEY
                                        else -> CalendarSource.TMDB
                                    }
                                )
                            )
                        }
                    }
                    Log.d(TAG, "TMDB $mediaType provider=$providerName: ${items.size} items from ${response.body()?.results?.size ?: 0} results")
                } else {
                    Log.w(TAG, "TMDB $mediaType provider=$providerName failed: ${response.code()} ${response.message()}")
                }
            } else {
                val response = tmdbApi.discoverTv(
                    apiKey = apiKey,
                    sortBy = "first_air_date.asc",
                    withWatchProviders = providerId.toString(),
                    watchRegion = "IT",
                    firstAirDateGte = dateFrom,
                    firstAirDateLte = dateTo
                )
                if (response.isSuccessful) {
                    response.body()?.results?.forEach { result ->
                        val releaseDate = parseLocalDate(result.firstAirDate)
                        if (releaseDate != null && !releaseDate.isBefore(LocalDate.now())) {
                            items.add(
                                CalendarItem(
                                    meta = result.toMetaPreview("tv"),
                                    releaseDate = releaseDate,
                                    addonName = providerName,
                                    source = when (providerId) {
                                        NETFLIX_PROVIDER_ID -> CalendarSource.NETFLIX
                                        PRIME_PROVIDER_ID -> CalendarSource.PRIME
                                        DISNEY_PROVIDER_ID -> CalendarSource.DISNEY
                                        else -> CalendarSource.TMDB
                                    }
                                )
                            )
                        }
                    }
                    Log.d(TAG, "TMDB $mediaType provider=$providerName: ${items.size} items from ${response.body()?.results?.size ?: 0} results")
                } else {
                    Log.w(TAG, "TMDB $mediaType provider=$providerName failed: ${response.code()} ${response.message()}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB $mediaType provider=$providerId failed", e)
            throw e
        }
        return items
    }

    private suspend fun fetchComingSoonRss(today: LocalDate): List<CalendarItem> {
        val items = mutableListOf<CalendarItem>()
        try {
            val request = Request.Builder()
                .url("https://www.comingsoon.it/rss/rss.asp?feed=filmprossim")
                .header("User-Agent", "NuvioTV/1.0")
                .build()

            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }

            if (response.isSuccessful) {
                val body = response.body?.string() ?: return emptyList()
                items.addAll(parseRss(body, today))
            }
        } catch (e: Exception) {
            Log.e(TAG, "ComingSoon RSS failed", e)
            throw e
        }
        return items
    }

    private fun parseRss(xml: String, today: LocalDate): List<CalendarItem> {
        val items = mutableListOf<CalendarItem>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var inItem = false
        var title = ""
        var description = ""
        var link = ""
        var pubDate = ""
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    if (currentTag == "item") {
                        inItem = true
                        title = ""
                        description = ""
                        link = ""
                        pubDate = ""
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val text = parser.text?.trim() ?: ""
                        when (currentTag) {
                            "title" -> title += text
                            "description" -> description += text
                            "link" -> link += text
                            "pubDate" -> pubDate += text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && inItem) {
                        inItem = false
                        val releaseDate = parseItalianDate(title, pubDate)
                        if (releaseDate != null && !releaseDate.isBefore(today)) {
                            items.add(
                                CalendarItem(
                                    meta = MetaPreview(
                                        id = "cs_${title.hashCode()}",
                                        type = ContentType.MOVIE,
                                        rawType = "movie",
                                        name = title,
                                        poster = null,
                                        posterShape = PosterShape.POSTER,
                                        background = null,
                                        logo = null,
                                        description = description.take(200),
                                        releaseInfo = null,
                                        imdbRating = null,
                                        genres = emptyList(),
                                        sourceAddonBaseUrl = null
                                    ),
                                    releaseDate = releaseDate,
                                    addonName = "Cinema Italia",
                                    source = CalendarSource.CINEMA
                                )
                            )
                        }
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun parseItalianDate(title: String, pubDate: String): LocalDate? {
        val monthMap = mapOf(
            "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4,
            "maggio" to 5, "giugno" to 6, "luglio" to 7, "agosto" to 8,
            "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12
        )

        val lowerTitle = title.lowercase()
        for ((monthName, monthNum) in monthMap) {
            val regex = Regex("(\\d{1,2})\\s+$monthName\\s+(\\d{4})")
            val match = regex.find(lowerTitle)
            if (match != null) {
                val day = match.groupValues[1].toIntOrNull() ?: continue
                val year = match.groupValues[2].toIntOrNull() ?: continue
                return try {
                    LocalDate.of(year, monthNum, day)
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
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
