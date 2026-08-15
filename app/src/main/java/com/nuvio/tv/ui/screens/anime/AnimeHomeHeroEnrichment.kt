package com.nuvio.tv.ui.screens.anime

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.first
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
    if (hasEssentialHeroFields(item)) return item
    val key = animeHeroEnrichmentCacheKey(item)
    animeHeroEnrichmentCache[key]?.let { cached -> return cached }
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
    return merged
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
