package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.OmdbApi
import com.nuvio.tv.data.remote.dto.omdb.OmdbResponseDto
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OmdbAwardsRepository @Inject constructor(
    private val api: OmdbApi,
    private val tmdbService: TmdbService
) {
    private data class CacheEntry(
        val awards: String?,
        val expiresAtMs: Long
    )

    private val tag = "OmdbAwardsRepository"
    private val cacheTtlMs = 24L * 60L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<String?>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun getAwards(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): String? = getAwards(
        imdbId = meta.imdbId,
        primaryId = meta.id,
        apiType = meta.apiType,
        fallbackItemId = fallbackItemId,
        fallbackItemType = fallbackItemType
    )

    /**
     * Resolves awards without a full [Meta]: usable straight from a catalog
     * MetaPreview so callers can start the OMDb lookup without waiting for the
     * addon meta round trip. The 24h cache and in-flight dedup are shared with
     * the meta-based overload, so a later call for the same title costs no
     * additional request.
     */
    suspend fun getAwards(
        imdbId: String?,
        primaryId: String?,
        apiType: String,
        fallbackItemId: String,
        fallbackItemType: String
    ): String? {
        val apiKey = BuildConfig.OMDB_API_KEY.trim()
        if (apiKey.isEmpty()) return null
        val imdbTitleId = resolveImdbId(imdbId, primaryId, apiType, fallbackItemId, fallbackItemType)
            ?: return null

        val cacheKey = "omdb:$imdbTitleId"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) return cached.awards
            cache.remove(cacheKey)
        }

        val deferred = inFlightMutex.withLock {
            inFlight[cacheKey] ?: scope.async {
                try {
                    val awards = fetchAwards(imdbTitleId, apiKey)
                    cache[cacheKey] = CacheEntry(
                        awards = awards,
                        expiresAtMs = System.currentTimeMillis() + cacheTtlMs
                    )
                    awards
                } finally {
                    inFlightMutex.withLock { inFlight.remove(cacheKey) }
                }
            }.also { created ->
                inFlight[cacheKey] = created
            }
        }
        return deferred.await()
    }

    private suspend fun fetchAwards(imdbId: String, apiKey: String): String? {
        val response = api.getTitle(imdbId = imdbId, apiKey = apiKey)
        if (!response.isSuccessful) {
            Log.w(tag, "OMDb request failed for $imdbId (${response.code()})")
            return null
        }
        return parseAwards(response.body())
    }

    private suspend fun resolveImdbId(
        imdbId: String?,
        primaryId: String?,
        apiType: String,
        fallbackItemId: String,
        fallbackItemType: String
    ): String? {
        extractImdbTitleId(imdbId)?.let { return it }
        extractImdbTitleId(primaryId)?.let { return it }
        extractImdbTitleId(fallbackItemId)?.let { return it }

        val tmdbId = extractTmdbId(primaryId)
            ?: extractTmdbId(fallbackItemId)
            ?: primaryId?.trim()?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: fallbackItemId.trim().takeIf { it.all(Char::isDigit) }?.toIntOrNull()
            ?: return null

        val mediaType = apiType.ifBlank { fallbackItemType }
        return tmdbService.tmdbToImdb(tmdbId, mediaType)?.takeIf { it.startsWith("tt") }
    }

    companion object {
        internal fun parseAwards(dto: OmdbResponseDto?): String? {
            if (dto == null) return null
            if (!dto.response.equals("True", ignoreCase = true)) return null
            return dto.awards?.trim()?.takeIf { it.isNotBlank() }
        }

        internal fun extractImdbTitleId(rawId: String?): String? {
            if (rawId.isNullOrBlank()) return null
            return Regex("tt\\d+").find(rawId)?.value
        }

        internal fun extractTmdbId(rawId: String?): Int? {
            if (rawId.isNullOrBlank()) return null
            val trimmed = rawId.trim()
            if (trimmed.startsWith("tmdb:", ignoreCase = true)) {
                return trimmed.substringAfter(':').substringBefore(':').toIntOrNull()
            }
            return null
        }
    }
}
