package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.ImdbTapframeApi
import com.nuvio.tv.data.remote.api.SeriesGraphApi
import com.nuvio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbEpisodeRatingsRepository @Inject constructor(
    private val imdbTapframeApi: ImdbTapframeApi,
    private val seriesGraphApi: SeriesGraphApi,
    private val tmdbApi: TmdbApi
) {
    private data class CacheEntry(
        val ratings: Map<Pair<Int, Int>, Double>,
        val expiresAtMs: Long
    )

    private val tag = "ImdbEpisodeRatingsRepo"
    private val cacheTtlMs = 30L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<Map<Pair<Int, Int>, Double>>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        val normalizedImdbId = imdbId
            ?.trim()
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?.substringBefore(':')
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedImdbId == null && normalizedTmdbId == null) return emptyMap()

        val cacheKey = when {
            !normalizedImdbId.isNullOrBlank() -> "imdb:$normalizedImdbId"
            else -> "tmdb:$normalizedTmdbId"
        }

        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.ratings
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    fetchEpisodeRatings(
                        imdbId = normalizedImdbId,
                        tmdbId = normalizedTmdbId
                    ).also { result ->
                        cache[cacheKey] = CacheEntry(
                            ratings = result,
                            expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                        )
                    }
                } finally {
                    inFlightMutex.withLock {
                        inFlight.remove(cacheKey)
                    }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }

        return deferred.await()
    }

    private suspend fun fetchEpisodeRatings(
        imdbId: String?,
        tmdbId: Int?
    ): Map<Pair<Int, Int>, Double> {
        if (tmdbId != null && tmdbId > 0) {
            val tmdbRatings = fetchFromTmdb(tmdbId)
            if (tmdbRatings.isNotEmpty()) return tmdbRatings
            Log.d(tag, "TMDB episode ratings empty for tmdbId=$tmdbId, trying other sources.")
        }

        if (!imdbId.isNullOrBlank()) {
            val primary = fetchFromImdbTapframe(imdbId)
            if (primary.isNotEmpty()) return primary
            Log.w(tag, "Primary episode ratings empty for imdbId=$imdbId, trying fallback.")
        }

        if (tmdbId != null && tmdbId > 0) {
            return fetchFromSeriesGraph(tmdbId)
        }

        return emptyMap()
    }

    private suspend fun fetchFromTmdb(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        return try {
            val apiKey = BuildConfig.TMDB_API_KEY
            if (apiKey.isBlank()) {
                Log.w(tag, "TMDB API key not configured")
                return emptyMap()
            }

            val detailsResponse = tmdbApi.getTvDetails(tmdbId, apiKey)
            if (!detailsResponse.isSuccessful) {
                Log.w(tag, "Failed to get TV details for tmdbId=$tmdbId (${detailsResponse.code()})")
                return emptyMap()
            }
            val numberOfSeasons = detailsResponse.body()?.numberOfSeasons
            if (numberOfSeasons == null || numberOfSeasons <= 0) {
                Log.w(tag, "No seasons found for tmdbId=$tmdbId")
                return emptyMap()
            }

            Log.d(tag, "Fetching TMDB episode ratings for tmdbId=$tmdbId ($numberOfSeasons seasons)")

            val allRatings = mutableMapOf<Pair<Int, Int>, Double>()

            val seasonResults = coroutineScope {
                (1..numberOfSeasons).map { seasonNumber ->
                    async {
                    try {
                        val seasonResponse = tmdbApi.getTvSeasonDetails(tmdbId, seasonNumber, apiKey)
                        if (!seasonResponse.isSuccessful) {
                            Log.w(tag, "Failed to get season $seasonNumber for tmdbId=$tmdbId (${seasonResponse.code()})")
                            return@async emptyList()
                        }
                        seasonResponse.body()?.episodes.orEmpty().mapNotNull { episode ->
                            val epNum = episode.episodeNumber ?: return@mapNotNull null
                            val rating = episode.voteAverage ?: return@mapNotNull null
                            if (rating > 0.0) {
                                Triple(seasonNumber, epNum, rating)
                            } else null
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "Error fetching season $seasonNumber for tmdbId=$tmdbId", e)
                        emptyList()
                    }
                }
                }.awaitAll()
            }
            seasonResults.flatten().forEach { (season, ep, rating) ->
                allRatings[season to ep] = rating
            }

            Log.d(tag, "TMDB: fetched ratings for ${allRatings.size} episodes across $numberOfSeasons seasons")
            allRatings
        } catch (e: Exception) {
            Log.w(tag, "Error fetching TMDB episode ratings for tmdbId=$tmdbId", e)
            emptyMap()
        }
    }

    private suspend fun fetchFromImdbTapframe(imdbId: String): Map<Pair<Int, Int>, Double> {
        return try {
            val response = imdbTapframeApi.getSeasonRatings(imdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed primary season ratings for imdbId=$imdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching primary season ratings for imdbId=$imdbId", e)
            emptyMap()
        }
    }

    private suspend fun fetchFromSeriesGraph(tmdbId: Int): Map<Pair<Int, Int>, Double> {
        return try {
            val response = seriesGraphApi.getSeasonRatings(tmdbId)
            if (!response.isSuccessful) {
                Log.w(tag, "Failed fallback season ratings for tmdbId=$tmdbId (${response.code()})")
                return emptyMap()
            }
            toRatingsMap(response.body().orEmpty())
        } catch (e: Exception) {
            Log.w(tag, "Error fetching fallback season ratings for tmdbId=$tmdbId", e)
            emptyMap()
        }
    }

    private fun toRatingsMap(payload: List<com.nuvio.tv.data.remote.api.SeriesGraphSeasonRatingsDto>): Map<Pair<Int, Int>, Double> {
        return buildMap {
            payload.forEach { season ->
                season.episodes.orEmpty().forEach { episode ->
                    val seasonNumber = episode.seasonNumber ?: return@forEach
                    val episodeNumber = episode.episodeNumber ?: return@forEach
                    val voteAverage = episode.voteAverage ?: return@forEach
                    put(seasonNumber to episodeNumber, voteAverage)
                }
            }
        }
    }
}
