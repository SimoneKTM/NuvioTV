package com.nuvio.tv.ui.screens.anime

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val ANIME_HERO_ENRICHMENT_TIMEOUT_MS = 6_000L
private const val ANIME_HERO_ENRICHMENT_CACHE_MAX_SIZE = 64

private val animeHeroEnrichmentCache = ConcurrentHashMap<String, MetaPreview?>()

internal fun animeHeroEnrichmentCacheKey(item: MetaPreview): String {
    return "${item.apiType}:${item.id}"
}

private fun hasEssentialHeroFields(item: MetaPreview): Boolean {
    return !item.logo.isNullOrBlank() &&
        !item.status.isNullOrBlank() &&
        !item.language.isNullOrBlank() &&
        !item.releaseInfo.isNullOrBlank()
}

internal suspend fun AnimeHomeViewModel.enrichAnimeHeroItem(item: MetaPreview): MetaPreview? {
    if (item.id.startsWith("__placeholder_")) return item
    if (hasEssentialHeroFields(item)) return applyAnimeHeroExternalEnrichment(item)
    val key = animeHeroEnrichmentCacheKey(item)
    animeHeroEnrichmentCache[key]?.let { cached ->
        return applyAnimeHeroExternalEnrichment(cached)
    }
    if (animeHeroEnrichmentCache.size >= ANIME_HERO_ENRICHMENT_CACHE_MAX_SIZE) {
        animeHeroEnrichmentCache.clear()
    }

    val idCandidates = buildList {
        add(item.id)
        if (item.id.startsWith("tmdb:")) add(item.id.substringAfter(':'))
    }.distinct()
    val typeCandidates = buildList {
        add(item.rawType)
        add(item.apiType)
        if (!item.apiType.equals(item.rawType, ignoreCase = true)) add(item.apiType)
    }.distinct()

    suspend fun tryResolve(useAllAddons: Boolean): Meta? {
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = runCatching {
                    withTimeoutOrNull(ANIME_HERO_ENRICHMENT_TIMEOUT_MS) {
                        val flow = if (useAllAddons) {
                            metaRepository.getMetaFromAllAddons(
                                type = type,
                                id = candidateId,
                                sourceAddonBaseUrl = item.sourceAddonBaseUrl
                            )
                        } else {
                            metaRepository.getMetaFromPrimaryAddon(type = type, id = candidateId)
                        }
                        flow.first { it !is NetworkResult.Loading }
                    }
                }.getOrNull()
                val meta = (result as? NetworkResult.Success<*>)?.data as? Meta
                if (meta != null) return meta
            }
        }
        return null
    }

    val resolved = tryResolve(useAllAddons = true) ?: tryResolve(useAllAddons = false)
    val merged = resolved?.mergeIntoAnimePreview(item) ?: item
    animeHeroEnrichmentCache[key] = merged
    return applyAnimeHeroExternalEnrichment(merged)
}

private suspend fun AnimeHomeViewModel.applyAnimeHeroExternalEnrichment(item: MetaPreview): MetaPreview {
    val tmdbSettings = currentAnimeTmdbSettings
    val mdbSettings = currentAnimeMdbListSettings
    if (!tmdbSettings.enabled &&
        !(mdbSettings.enabled && mdbSettings.apiKey.isNotBlank())
    ) {
        return item
    }
    val (enrichment, mdbRating) = fetchAnimeExternalEnrichment(
        itemId = item.id,
        itemType = item.apiType,
        contentType = item.type,
        tmdbSettings = tmdbSettings,
        mdbSettings = mdbSettings
    )
    return applyAnimeTmdbToPreview(
        item = item,
        enrichment = enrichment,
        settings = tmdbSettings
    ).let { preview ->
        if (mdbRating != null) preview.copy(imdbRating = mdbRating.toFloat()) else preview
    }
}

internal suspend fun AnimeHomeViewModel.applyAnimeExternalEnrichmentToCwMeta(
    summary: CwMetaSummary,
    itemType: String
): CwMetaSummary {
    val tmdbSettings = currentAnimeTmdbSettings
    val mdbSettings = currentAnimeMdbListSettings
    val tmdbEnabled = tmdbSettings.enabled && tmdbSettings.enrichContinueWatching
    val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()
    if (!tmdbEnabled && !mdbEnabled) return summary

    val contentType = when (itemType.lowercase()) {
        "movie", "film" -> ContentType.MOVIE
        else -> ContentType.SERIES
    }
    val (enrichment, mdbRating) = fetchAnimeExternalEnrichment(
        itemId = summary.id,
        itemType = itemType,
        contentType = contentType,
        tmdbSettings = tmdbSettings,
        mdbSettings = mdbSettings
    )
    var enriched = summary
    if (enrichment != null) {
        if (tmdbSettings.useArtwork) {
            enriched = enriched.copy(
                backdropUrl = enrichment.backdrop ?: enriched.backdropUrl,
                poster = enrichment.poster ?: enriched.poster,
                logo = enrichment.logo ?: enriched.logo
            )
        }
        if (tmdbSettings.useBasicInfo) {
            enriched = enriched.copy(
                description = enrichment.description ?: enriched.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres
            )
        }
        if (tmdbSettings.useDetails) {
            enriched = enriched.copy(
                country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                language = enrichment.language ?: enriched.language
            )
        }
        if (tmdbSettings.useReleaseDates) {
            enriched = enriched.copy(
                releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
            )
        }
    }
    if (mdbRating != null) {
        enriched = enriched.copy(imdbRating = mdbRating.toFloat())
    }
    return enriched
}

private suspend fun AnimeHomeViewModel.fetchAnimeExternalEnrichment(
    itemId: String,
    itemType: String,
    contentType: ContentType,
    tmdbSettings: TmdbSettings,
    mdbSettings: MDBListSettings
): Pair<TmdbEnrichment?, Double?> = withContext(Dispatchers.IO) {
    val tmdbEnabled = tmdbSettings.enabled
    val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()
    if (!tmdbEnabled && !mdbEnabled) return@withContext null to null

    val tmdbEnrichment = if (tmdbEnabled) {
        val tmdbId = runCatching { tmdbService.ensureTmdbId(itemId, itemType) }.getOrNull()
        if (tmdbId != null) {
            runCatching {
                tmdbMetadataService.fetchEnrichment(
                    tmdbId = tmdbId,
                    contentType = contentType,
                    language = tmdbSettings.language
                )
            }.getOrNull()
        } else {
            null
        }
    } else {
        null
    }

    val mdbRating = if (mdbEnabled) {
        runCatching {
            mdbListRepository.getImdbRatingForItemWithSettings(itemId, itemType, mdbSettings)
        }.getOrNull()
    } else {
        null
    }

    tmdbEnrichment to mdbRating
}

private fun applyAnimeTmdbToPreview(
    item: MetaPreview,
    enrichment: TmdbEnrichment?,
    settings: TmdbSettings
): MetaPreview {
    var enriched = item
    if (enrichment == null) return enriched
    if (settings.useArtwork) {
        enriched = enriched.copy(
            background = enrichment.backdrop ?: enriched.background,
            logo = enrichment.logo ?: enriched.logo,
            poster = enrichment.poster ?: enriched.poster
        )
    }
    if (settings.useBasicInfo) {
        enriched = enriched.copy(
            description = enrichment.description ?: enriched.description,
            genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres
        )
    }
    if (settings.useDetails) {
        enriched = enriched.copy(
            runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
            status = enrichment.status ?: enriched.status,
            ageRating = enrichment.ageRating ?: enriched.ageRating,
            country = enrichment.countries?.joinToString(", ") ?: enriched.country,
            language = enrichment.language ?: enriched.language
        )
    }
    if (settings.useReleaseDates) {
        enriched = enriched.copy(
            releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
        )
    }
    return enriched
}

internal suspend fun AnimeHomeViewModel.enrichAnimeHeroItemsBatch(
    items: List<MetaPreview>
): List<MetaPreview> {
    if (items.isEmpty()) return items
    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                runCatching { applyAnimeHeroExternalEnrichment(item) }.getOrDefault(item)
            }
        }.awaitAll()
    }
}

internal fun AnimeHomeViewModel.animeHeroEnrichmentSignature(
    items: List<MetaPreview>
): String {
    val tmdbSettings = currentAnimeTmdbSettings
    val mdbEnabled = currentAnimeMdbListSettings.enabled &&
        currentAnimeMdbListSettings.apiKey.isNotBlank()
    val itemSignature = items.joinToString(separator = "|") { item ->
        "${item.id}:${item.apiType}:${item.name}:${item.background}:${item.logo}:${item.poster}"
    }
    return buildString {
        append(tmdbSettings.enabled)
        append(':')
        append(tmdbSettings.language)
        append(':')
        append(tmdbSettings.useArtwork)
        append(':')
        append(tmdbSettings.useBasicInfo)
        append(':')
        append(tmdbSettings.useDetails)
        append(':')
        append(tmdbSettings.useReleaseDates)
        append(':')
        append(mdbEnabled)
        append("::")
        append(itemSignature)
    }
}

private fun Meta.mergeIntoAnimePreview(preview: MetaPreview): MetaPreview {
    return preview.copy(
        name = name,
        description = description ?: preview.description,
        logo = logo ?: preview.logo,
        poster = poster ?: preview.poster,
        background = background ?: preview.background,
        landscapePoster = landscapePoster ?: preview.landscapePoster,
        releaseInfo = releaseInfo ?: preview.releaseInfo,
        released = released ?: preview.released,
        imdbRating = imdbRating ?: preview.imdbRating,
        imdbId = imdbId ?: preview.imdbId,
        genres = if (genres.isNotEmpty()) genres else preview.genres,
        runtime = runtime ?: preview.runtime,
        status = status ?: preview.status,
        ageRating = ageRating ?: preview.ageRating,
        country = country ?: preview.country,
        language = language ?: preview.language
    )
}

internal fun normalizeAnimeStatus(status: String?): String? {
    if (status.isNullOrBlank()) return null
    return when (status.trim().lowercase()) {
        "finished", "finished airing", "complete", "completed", "aired" -> "ended"
        "currently airing", "airing", "airing now", "ongoing", "on air", "releasing" -> "continuing"
        "not yet aired", "upcoming", "unreleased", "tba", "to be announced", "to be determined" -> "planned"
        else -> status.trim()
    }
}
