package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.OmdbApi
import com.nuvio.tv.data.remote.dto.omdb.OmdbResponseDto
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.systemMetadataLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OmdbAwardsRepository @Inject constructor(
    private val api: OmdbApi,
    private val tmdbService: TmdbService,
    @ApplicationContext private val context: Context,
    private val metadataTextTranslator: MetadataTextTranslator
) {
    private data class CacheEntry(
        val awards: String?,
        val expiresAtMs: Long
    )

    /** raw == null means "known to have no awards" (negative cache entry). */
    private data class CacheLookup(val raw: String?, val fresh: Boolean)

    private val tag = "OmdbAwardsRepository"
    private val cacheTtlMs = 24L * 60L * 60L * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<String?>>()
    private val inFlightMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val disk by lazy {
        OmdbAwardsDiskCache(File(context.filesDir, DISK_CACHE_FILE))
    }

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
     * addon meta round trip. Memory + disk caches and in-flight dedup are
     * shared across all overloads, so later calls for the same title — even
     * after a process restart — cost no request at all.
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

        val now = System.currentTimeMillis()
        val localKeys = candidateCacheKeys(primaryId, apiType, fallbackItemId, fallbackItemType)
        val embeddedImdbId = extractImdbTitleId(imdbId)
            ?: extractImdbTitleId(primaryId)
            ?: extractImdbTitleId(fallbackItemId)

        // Fast path: answer from memory/disk without resolving ids or network.
        lookupCached(localKeys + embeddedImdbId?.let { listOf(imdbKey(it)) }.orEmpty(), now)
            ?.let { hit ->
                if (!hit.fresh) {
                    revalidateAsync(apiKey, embeddedImdbId, primaryId, apiType, fallbackItemId, fallbackItemType)
                }
                return hit.raw?.let { localizeAwards(it) }
            }

        val imdbTitleId = resolveImdbId(imdbId, primaryId, apiType, fallbackItemId, fallbackItemType)
            ?: return null
        val allKeys = localKeys + imdbKey(imdbTitleId)
        lookupCached(allKeys, now)?.let { hit ->
            if (!hit.fresh) {
                revalidateAsync(apiKey, embeddedImdbId, primaryId, apiType, fallbackItemId, fallbackItemType)
            }
            return hit.raw?.let { localizeAwards(it) }
        }

        val raw = fetchAwardsDeduped(imdbTitleId, apiKey, allKeys)
        rememberAwards(allKeys, raw, now)
        return raw?.let { localizeAwards(it) }
    }

    /**
     * Stale-while-revalidate: the caller gets the cached string instantly and
     * a background refresh (shared in-flight dedup) updates memory + disk.
     */
    private fun revalidateAsync(
        apiKey: String,
        embeddedImdbId: String?,
        primaryId: String?,
        apiType: String,
        fallbackItemId: String,
        fallbackItemType: String
    ) {
        scope.launch {
            runCatching {
                val imdbTitleId = resolveImdbId(
                    imdbId = embeddedImdbId,
                    primaryId = primaryId,
                    apiType = apiType,
                    fallbackItemId = fallbackItemId,
                    fallbackItemType = fallbackItemType
                ) ?: return@runCatching
                val keys = candidateCacheKeys(primaryId, apiType, fallbackItemId, fallbackItemType) +
                    imdbKey(imdbTitleId) +
                    embeddedImdbId?.let { listOf(imdbKey(it)) }.orEmpty()
                val fetched = fetchAwardsDeduped(imdbTitleId, apiKey, keys)
                // Never downgrade a good cached string to a transient failure.
                if (fetched != null) {
                    rememberAwards(keys, fetched, System.currentTimeMillis())
                }
            }.onFailure {
                Log.w(tag, "OMDb background revalidation failed: ${it.message}")
            }
        }
    }

    private suspend fun lookupCached(keys: Collection<String>, now: Long): CacheLookup? =
        withContext(Dispatchers.IO) {
            for (key in keys) {
                val entry = cache[key]
                if (entry != null) {
                    return@withContext CacheLookup(raw = entry.awards, fresh = entry.expiresAtMs > now)
                }
            }
            for (key in keys) {
                val entry = disk.get(key)
                val raw = entry?.raw
                if (entry != null && !raw.isNullOrBlank()) {
                    val expiresAtMs = entry.fetchedAtMs + cacheTtlMs
                    cache[key] = CacheEntry(awards = raw, expiresAtMs = expiresAtMs)
                    return@withContext CacheLookup(raw = raw, fresh = expiresAtMs > now)
                }
            }
            null
        }

    private suspend fun rememberAwards(keys: Collection<String>, raw: String?, fetchedAtMs: Long) {
        if (keys.isEmpty()) return
        val expiresAtMs = fetchedAtMs + cacheTtlMs
        keys.forEach { key -> cache[key] = CacheEntry(awards = raw, expiresAtMs = expiresAtMs) }
        if (raw != null) {
            withContext(Dispatchers.IO) { disk.putAll(keys, raw, fetchedAtMs) }
        }
    }

    /**
     * Returns the device-language OMDb awards text (translated on-device via
     * ML Kit the first time per string; the persistent translation cache makes
     * every later read instant). Falls back to the raw English text.
     */
    private suspend fun localizeAwards(raw: String): String {
        if (raw.isBlank()) return raw
        return try {
            metadataTextTranslator.translateAwardText(raw, systemMetadataLanguage()) ?: raw
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "Awards translation failed: ${e.message}")
            raw
        }
    }

    /**
     * Fetch with in-flight dedup. A successful result is cached from inside
     * the repository scope so it survives the caller being cancelled (user
     * leaving the screen mid-fetch); the caller repeats the store afterwards
     * to cover joiners whose key set differs from the creator's.
     */
    private suspend fun fetchAwardsDeduped(
        imdbTitleId: String,
        apiKey: String,
        cacheKeys: Collection<String>
    ): String? {
        val requestKey = imdbKey(imdbTitleId)
        val deferred = inFlightMutex.withLock {
            inFlight[requestKey] ?: scope.async {
                try {
                    val fetched = fetchAwards(imdbTitleId, apiKey)
                    if (fetched != null) {
                        rememberAwards(cacheKeys, fetched, System.currentTimeMillis())
                    }
                    fetched
                } finally {
                    inFlightMutex.withLock { inFlight.remove(requestKey) }
                }
            }.also { created ->
                inFlight[requestKey] = created
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
        private const val DISK_CACHE_FILE = "omdb_awards_cache.json"
        private val IMDB_ID_REGEX = Regex("tt\\d+", RegexOption.IGNORE_CASE)

        internal fun imdbKey(imdbTitleId: String): String = "omdb:$imdbTitleId"

        /**
         * Request identities resolvable without any network hop. Stored after
         * the first successful fetch so later opens skip both the TMDB
         * id translation and the OMDb request entirely.
         */
        internal fun candidateCacheKeys(
            primaryId: String?,
            apiType: String?,
            fallbackItemId: String?,
            fallbackItemType: String?
        ): List<String> {
            fun keyFor(id: String?, type: String?): String? {
                val trimmedId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (IMDB_ID_REGEX.containsMatchIn(trimmedId)) return null
                val normalizedType = type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "unknown"
                return "omdb:id:$normalizedType:$trimmedId"
            }
            return listOfNotNull(
                keyFor(primaryId, apiType),
                keyFor(fallbackItemId, fallbackItemType)
            ).distinct()
        }

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
