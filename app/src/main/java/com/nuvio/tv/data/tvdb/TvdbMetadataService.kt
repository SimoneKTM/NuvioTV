package com.nuvio.tv.data.tvdb

import android.util.Log
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.MetaTrailer
import com.nuvio.tv.domain.model.TvdbSettings
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbMetadataService @Inject constructor(
    private val api: TvdbApi
) {
    private val tag = "TvdbMetadata"

    data class EnrichmentResult(
        val meta: Meta,
        val rating: Float? = null,
        val trailers: List<MetaTrailer> = emptyList()
    )

    /** Lightweight fields TVDB can fill on a Home MetaPreview. */
    data class PreviewEnrichment(
        val description: String? = null,
        val background: String? = null
    )

    // In-memory caches (parity with TmdbMetadataService)
    private val seriesExtendedCache = ConcurrentHashMap<String, TvdbApi.TvdbSeriesExtended>()
    private val seriesExtendedInFlight = ConcurrentHashMap<String, CompletableDeferred<TvdbApi.TvdbSeriesExtended?>>()
    private val seriesIdCache = ConcurrentHashMap<String, String>()
    private val seriesIdInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val episodeCache = ConcurrentHashMap<String, Map<Pair<Int, Int>, TvdbEpisodeEnrichment>>()
    private val previewCache = ConcurrentHashMap<String, PreviewEnrichment>()

    suspend fun enrichSeries(meta: Meta, fallbackItemId: String, settings: TvdbSettings): EnrichmentResult {
        if (!settings.enabled || !settings.hasApiKey) return EnrichmentResult(meta)
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return EnrichmentResult(meta)

        return withContext(Dispatchers.Default) {
            try {
                val seriesId = findSeriesId(apiKey, fallbackItemId, meta.name) ?: return@withContext EnrichmentResult(meta)
                val apiLanguage = settings.language.takeIf { it.isNotBlank() }?.trim()

                val extended = getSeriesExtendedCached(apiKey, seriesId, apiLanguage)
                    ?: return@withContext EnrichmentResult(meta)

                val needsEpisodes = settings.useEpisodes || settings.useSeasonPosters
                val episodeMap = if (needsEpisodes && extended.id > 0) {
                    val seasonPosterMap = if (settings.useSeasonPosters) {
                        extended.seasons
                            .filter { it.image != null }
                            .associate { it.number to it.image }
                    } else {
                        emptyMap()
                    }
                    fetchEpisodeEnrichment(apiKey, extended.id, seasonPosterMap, language = apiLanguage)
                } else {
                    emptyMap()
                }

                var enriched = meta

                if (settings.useBasicInfo && apiLanguage != null && apiLanguage != "en") {
                    val localizedName = extended.aliases
                        .firstOrNull { it.language == apiLanguage && it.name.isNotBlank() }
                        ?.name
                        ?: extended.name.takeIf { it.isNotBlank() }
                    if (localizedName != null) {
                        enriched = enriched.copy(name = localizedName)
                    }
                }

                if (settings.useBasicInfo && extended.overview != null && extended.overview.isNotBlank()) {
                    if (enriched.description.isNullOrBlank()) {
                        enriched = enriched.copy(description = extended.overview)
                    }
                }

                if (settings.useArtwork && extended.image != null && extended.image.isNotBlank()) {
                    enriched = enriched.copy(
                        background = extended.image.takeIf { enriched.background.isNullOrBlank() } ?: enriched.background
                    )
                }

                val rating = if (settings.useBasicInfo) {
                    extended.contentRatings.firstOrNull()?.rating?.takeIf { it > 0f }
                } else {
                    null
                }

                val trailers = if (settings.useTrailers && extended.trailers.isNotEmpty() && enriched.trailers.isEmpty()) {
                    extended.trailers.mapNotNull { trailer ->
                        trailer.url?.let { url ->
                            extractYouTubeVideoId(url)?.let { ytId ->
                                MetaTrailer(
                                    source = "tvdb",
                                    type = "trailer",
                                    name = trailer.name ?: "Trailer",
                                    ytId = ytId,
                                    lang = trailer.language ?: apiLanguage
                                )
                            }
                        }
                    }
                } else {
                    emptyList()
                }

                if (settings.useCredits && extended.characters.isNotEmpty()) {
                    val people = extended.characters.mapNotNull { character ->
                        val actorName = character.personName?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        MetaCastMember(
                            name = actorName,
                            character = character.name.trim().takeIf { it.isNotBlank() },
                            photo = character.image?.takeIf { it.isNotBlank() },
                            tmdbId = null
                        )
                    }
                    enriched = enriched.copy(castMembers = people)
                }

                if (episodeMap.isNotEmpty()) {
                    enriched = enriched.copy(
                        videos = meta.videos.map { video ->
                            val key = video.season?.let { season ->
                                video.episode?.let { episode -> season to episode }
                            }
                            val episodeData = key?.let(episodeMap::get)
                            if (episodeData == null) {
                                video
                            } else {
                                video.copy(
                                    title = if (settings.useEpisodes) {
                                        episodeData.title ?: video.title
                                    } else {
                                        video.title
                                    },
                                    overview = if (settings.useEpisodes) {
                                        episodeData.overview ?: video.overview
                                    } else {
                                        video.overview
                                    },
                                    thumbnail = if (settings.useEpisodes) {
                                        episodeData.thumbnail ?: video.thumbnail
                                    } else {
                                        video.thumbnail
                                    },
                                    released = if (settings.useEpisodes) {
                                        episodeData.airDate ?: video.released
                                    } else {
                                        video.released
                                    },
                                    runtime = if (settings.useEpisodes) {
                                        episodeData.runtimeMinutes ?: video.runtime
                                    } else {
                                        video.runtime
                                    },
                                    seasonPoster = if (settings.useSeasonPosters) {
                                        episodeData.seasonPoster ?: video.seasonPoster
                                    } else {
                                        video.seasonPoster
                                    }
                                )
                            }
                        }
                    )
                }

                Log.d(tag, "TVDB enriched ${meta.name}: seriesId=$seriesId")
                EnrichmentResult(
                    meta = enriched,
                    rating = rating,
                    trailers = trailers
                )
            } catch (e: Exception) {
                Log.w(tag, "TVDB enrichment failed: ${e.message}")
                EnrichmentResult(meta)
            }
        }
    }

    /**
     * Lightweight enrichment for Home MetaPreview items (focus/hero/CW).
     * Fills description/background when blank. Series only — movies return null.
     */
    suspend fun enrichPreview(
        itemId: String,
        name: String,
        apiType: String,
        settings: TvdbSettings
    ): PreviewEnrichment? {
        if (!settings.enabled || !settings.hasApiKey) return null
        if (apiType !in listOf("series", "tv")) return null
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return null

        val cacheKey = "$itemId:$name:${settings.language}"
        previewCache[cacheKey]?.let { return it }

        return withContext(Dispatchers.Default) {
            try {
                val seriesId = findSeriesId(apiKey, itemId, name) ?: return@withContext null
                val apiLanguage = settings.language.takeIf { it.isNotBlank() }?.trim()
                val extended = getSeriesExtendedCached(apiKey, seriesId, apiLanguage)
                    ?: return@withContext null

                val result = PreviewEnrichment(
                    description = if (settings.useBasicInfo) {
                        extended.overview?.takeIf { it.isNotBlank() }
                    } else null,
                    background = if (settings.useArtwork) {
                        extended.image?.takeIf { it.isNotBlank() }
                    } else null
                )
                if (result.description != null || result.background != null) {
                    previewCache[cacheKey] = result
                }
                result
            } catch (e: Exception) {
                Log.w(tag, "TVDB preview enrichment failed: ${e.message}")
                null
            }
        }
    }

    /** Apply a PreviewEnrichment to a MetaPreview, filling only blank fields (TMDB wins). */
    fun applyPreviewToItem(item: MetaPreview, enrichment: PreviewEnrichment?): MetaPreview {
        if (enrichment == null) return item
        var merged = item
        if (merged.description.isNullOrBlank() && !enrichment.description.isNullOrBlank()) {
            merged = merged.copy(description = enrichment.description)
        }
        if (merged.background.isNullOrBlank() && !enrichment.background.isNullOrBlank()) {
            merged = merged.copy(background = enrichment.background)
        }
        return merged
    }

    private suspend fun getSeriesExtendedCached(
        apiKey: String,
        seriesId: String,
        language: String?
    ): TvdbApi.TvdbSeriesExtended? {
        val cacheKey = "$seriesId:${language ?: "default"}"
        seriesExtendedCache[cacheKey]?.let { return it }
        seriesExtendedInFlight[cacheKey]?.let { return it.await() }

        val deferred = CompletableDeferred<TvdbApi.TvdbSeriesExtended?>()
        seriesExtendedInFlight.putIfAbsent(cacheKey, deferred)?.let { existing ->
            return existing.await()
        }
        try {
            val result = api.getSeriesExtended(apiKey, seriesId, language = language)
            if (result != null) {
                seriesExtendedCache[cacheKey] = result
            }
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.complete(null)
            throw e
        } finally {
            seriesExtendedInFlight.remove(cacheKey)
        }
    }

    private suspend fun findSeriesId(apiKey: String, fallbackItemId: String, name: String?): String? {
        val idCacheKey = "$fallbackItemId:${name.orEmpty()}"
        seriesIdCache[idCacheKey]?.let { return it }

        val remoteResult = tryRemoteIdSearch(apiKey, fallbackItemId)
        if (remoteResult != null) {
            seriesIdCache[idCacheKey] = remoteResult
            return remoteResult
        }

        val searchName = name?.takeIf { it.isNotBlank() } ?: return null

        val results = api.searchSeries(apiKey, searchName)
        if (results.isNotEmpty()) {
            seriesIdCache[idCacheKey] = results.first().id
            return results.first().id
        }

        val simplifiedName = searchName
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .trim()
        if (simplifiedName != searchName) {
            val retryResults = api.searchSeries(apiKey, simplifiedName)
            if (retryResults.isNotEmpty()) {
                seriesIdCache[idCacheKey] = retryResults.first().id
                return retryResults.first().id
            }
        }

        return null
    }

    private suspend fun fetchEpisodeEnrichment(
        apiKey: String,
        seriesId: Int,
        seasonPosterMap: Map<Int, String?>,
        language: String? = null
    ): Map<Pair<Int, Int>, TvdbEpisodeEnrichment> {
        val cacheKey = "$seriesId:${language ?: "default"}:${seasonPosterMap.hashCode()}"
        episodeCache[cacheKey]?.let { return it }

        val result = mutableMapOf<Pair<Int, Int>, TvdbEpisodeEnrichment>()
        var page = 0
        while (true) {
            val response = api.getSeriesEpisodes(apiKey, seriesId, page, language = language) ?: break
            for (episode in response.data) {
                val key = episode.seasonNumber to episode.number
                if (key !in result) {
                    result[key] = TvdbEpisodeEnrichment(
                        title = episode.name?.trim()?.takeIf(String::isNotBlank),
                        overview = episode.overview?.trim()?.takeIf(String::isNotBlank),
                        thumbnail = episode.image?.takeIf(String::isNotBlank),
                        seasonPoster = seasonPosterMap[episode.seasonNumber],
                        airDate = episode.airDate?.trim()?.takeIf(String::isNotBlank),
                        runtimeMinutes = episode.runtime
                    )
                }
            }
            val next = response.links?.next ?: break
            page = next
        }
        episodeCache[cacheKey] = result
        return result
    }

    private data class TvdbEpisodeEnrichment(
        val title: String? = null,
        val overview: String? = null,
        val thumbnail: String? = null,
        val seasonPoster: String? = null,
        val airDate: String? = null,
        val runtimeMinutes: Int? = null
    )

    private suspend fun tryRemoteIdSearch(apiKey: String, itemId: String): String? {
        val remoteId = when {
            itemId.matches(Regex("^tt\\d+$")) -> "imdb:$itemId"
            itemId.matches(Regex("^\\d+$")) -> "tmdb:$itemId"
            else -> return null
        }
        val results = api.searchByRemoteId(apiKey, remoteId)
        return results.firstOrNull()?.id
    }

    private fun extractYouTubeVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.matches(YOUTUBE_VIDEO_ID_REGEX)) return trimmed

        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return@runCatching null
            when {
                host == "youtu.be" -> {
                    val id = uri.path?.trim('/')?.substringBefore('/')?.trim().orEmpty()
                    id.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                }

                host == "youtube.com" || host.endsWith(".youtube.com") -> {
                    val path = uri.path.orEmpty()
                    val query = uri.rawQuery.orEmpty()

                    if (path.startsWith("/watch")) {
                        query.split("&")
                            .asSequence()
                            .mapNotNull { entry ->
                                val index = entry.indexOf('=')
                                if (index <= 0) return@mapNotNull null
                                val key = entry.substring(0, index)
                                val value = entry.substring(index + 1)
                                if (key == "v") value else null
                            }
                            .firstOrNull { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                    } else {
                        val segments = path.trim('/').split("/")
                        val candidate = when (segments.firstOrNull()?.lowercase()) {
                            "embed", "shorts", "live" -> segments.getOrNull(1)
                            else -> null
                        }
                        candidate?.takeIf { it.matches(YOUTUBE_VIDEO_ID_REGEX) }
                    }
                }

                else -> null
            }
        }.getOrNull()
    }

    private companion object {
        val YOUTUBE_VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    }
}
