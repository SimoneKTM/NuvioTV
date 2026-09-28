package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestLogoUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ten most-watched shows of the current month from Trakt, powering the
 * Netflix-style Top 10 home row. The chart keeps Trakt's rank order and
 * results are cached in memory for 24h. Network failures fall back to the
 * last good list; when there is no cache at all the failure is surfaced so
 * the caller (Home / warm-up) can retry instead of losing the row for the
 * whole session.
 */
@Singleton
class TraktTop10Repository @Inject constructor(
    private val traktApi: TraktApi
) {
    companion object {
        private const val TAG = "TraktTop10"
        private const val TOP_LIMIT = 10
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchMutex = Mutex()

    @Volatile
    private var cachedItems: List<MetaPreview> = emptyList()

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var warmUpJob: Job? = null

    fun warmUp() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = scope.launch {
            try {
                getTopShows()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "warmUp failed: ${e.message}")
            }
        }
    }

    suspend fun getTopShows(): List<MetaPreview> {
        if (isFresh()) return cachedItems
        return fetchMutex.withLock {
            if (isFresh()) return@withLock cachedItems
            val fetched = fetchTopShows()
                ?: return@withLock cachedItems.ifEmpty {
                    // Nothing cached yet: surface the failure so callers can
                    // retry instead of hiding the Top 10 row all session.
                    throw IllegalStateException("Trakt most-watched request failed")
                }
            if (fetched.isNotEmpty()) {
                cachedItems = fetched
                cachedAtMs = System.currentTimeMillis()
            }
            fetched
        }
    }

    private fun isFresh(): Boolean {
        return cachedItems.isNotEmpty() &&
            System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS
    }

    private suspend fun fetchTopShows(): List<MetaPreview>? {
        return try {
            val response = traktApi.getMostWatchedShowsMonthly(limit = TOP_LIMIT)
            if (response.isSuccessful) {
                mapTopShowPreviews(response.body().orEmpty())
            } else {
                Log.w(TAG, "most watched shows failed: ${response.code()} ${response.message()}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "most watched shows failed: ${e.message}")
            null
        }
    }
}

/**
 * Pure mapping from Trakt's most-watched chart DTOs to home-row previews.
 * Items keep Trakt's rank order; entries without a show object are dropped
 * and a missing title maps to an empty name.
 */
internal fun mapTopShowPreviews(
    dtos: List<TraktWatchedShowItemDto>,
    limit: Int = 10
): List<MetaPreview> {
    return dtos.asSequence()
        .mapNotNull { it.show }
        .take(limit)
        .map { it.toTopShowPreview() }
        .toList()
}

private fun TraktShowDto.toTopShowPreview(): MetaPreview {
    val images = images
    return MetaPreview(
        id = buildTopShowContentId(
            imdb = ids?.imdb,
            tmdb = ids?.tmdb,
            trakt = ids?.trakt
        ),
        type = ContentType.SERIES,
        rawType = "tv",
        name = title ?: "",
        poster = images.traktBestPosterUrl(),
        posterShape = PosterShape.POSTER,
        background = images.traktBestBackdropUrl(),
        logo = images.traktBestLogoUrl(),
        description = overview?.takeIf { it.isNotBlank() },
        releaseInfo = year?.toString(),
        imdbRating = rating?.toFloat(),
        genres = genres.orEmpty(),
        imdbId = ids?.imdb,
        slug = ids?.slug,
        sourceAddonBaseUrl = null
    )
}

/**
 * Prefer IMDB (best addon compatibility), then TMDB, then Trakt — same
 * ordering as the calendar mapping in CalendarRepositoryImpl.
 */
private fun buildTopShowContentId(imdb: String?, tmdb: Int?, trakt: Int?): String {
    val imdbId = imdb?.trim()?.takeIf { it.isNotBlank() }
    if (imdbId != null) return imdbId
    if (tmdb != null) return "tmdb_tv_$tmdb"
    return "trakt_tv_${trakt ?: 0}"
}
