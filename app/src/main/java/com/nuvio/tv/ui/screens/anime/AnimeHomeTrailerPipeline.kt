package com.nuvio.tv.ui.screens.anime

import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.extractYear
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ANIME_TRAILER_REQUEST_DEBOUNCE_MS = 180L

// kitsu:/mal:/anilist: prefixed ids are NOT TMDB ids: ensureTmdbId strips the
// prefix and would return the raw anime id as if it were a TMDB id (see
// StreamRepositoryImpl), so those must never go through ensureTmdbId directly.
private val ANIME_EXTERNAL_ID_PREFIXES = listOf("kitsu:", "mal:", "anilist:")

private fun SnapshotStateMap<String, String>.putUrl(itemId: String, url: String) {
    if (this[itemId] != url) this[itemId] = url
}

/**
 * Resolve a TMDB id for an anime catalog item without falling into the
 * "anime id treated as TMDB id" trap. External-id items fall back to the
 * addon-provided IMDb id; without one we return null and let the caller use
 * localized YouTube ids from enrichment instead.
 */
private suspend fun AnimeHomeViewModel.resolveAnimeTmdbId(item: MetaPreview): String? {
    val id = item.id
    val isAnimeExternalId = ANIME_EXTERNAL_ID_PREFIXES.any { id.startsWith(it, ignoreCase = true) }
    if (isAnimeExternalId) {
        val imdbId = item.imdbId
            ?.trim()
            ?.substringBefore(':')
            ?.takeIf { it.startsWith("tt", ignoreCase = true) }
            ?: return null
        return runCatching { tmdbService.ensureTmdbId(imdbId, item.apiType) }.getOrNull()
    }
    return runCatching { tmdbService.ensureTmdbId(id, item.apiType) }.getOrNull()
}

/**
 * Anime-tab trailer preview resolution. Same shape as
 * HomeViewModel.requestTrailerPreviewPipeline but scoped to this tab:
 * - gates on the anime TMDB enrichment settings (not the shared main ones)
 * - never feeds kitsu:/mal:/anilist: ids to ensureTmdbId
 * - falls back to localized YT ids brought by meta/TMDB enrichment.
 */
internal fun AnimeHomeViewModel.requestTrailerPreview(item: MetaPreview) {
    if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
    if (item.apiType == "channel") return
    if (item.id.startsWith("__placeholder_")) return

    activeTrailerPreviewItemId = item.id
    trailerPreviewRequestVersion++
    val requestVersion = trailerPreviewRequestVersion

    if (item.id in trailerPreviewNegativeCache) return
    if (trailerPreviewUrlsState.containsKey(item.id)) return
    if (!trailerPreviewLoadingIds.add(item.id)) return

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Debounce: wait for focus to settle before hitting the network.
            delay(ANIME_TRAILER_REQUEST_DEBOUNCE_MS)
            if (trailerPreviewRequestVersion != requestVersion) return@launch

            val fallbackYtId = item.trailerYtIds.firstOrNull()
            val tmdbId = resolveAnimeTmdbId(item)
            val trailerSource = trailerService.getTrailerPlaybackSource(
                title = item.name,
                year = extractYear(item.releaseInfo),
                tmdbId = tmdbId,
                type = item.apiType,
                tmdbSettingsOverride = currentAnimeTmdbSettings
            )

            withContext(Dispatchers.Main) {
                if (trailerSource?.videoUrl.isNullOrBlank()) {
                    val fallbackSource = fallbackYtId?.let { ytId ->
                        trailerService.getTrailerPlaybackSourceFromYouTubeUrl(
                            youtubeUrl = "https://www.youtube.com/watch?v=$ytId",
                            title = item.name,
                            year = extractYear(item.releaseInfo)
                        )
                    }
                    if (fallbackSource?.videoUrl != null) {
                        trailerPreviewUrlsState.putUrl(item.id, fallbackSource.videoUrl)
                        val fallbackAudio = fallbackSource.audioUrl
                        if (fallbackAudio.isNullOrBlank()) {
                            trailerPreviewAudioUrlsState.remove(item.id)
                        } else {
                            trailerPreviewAudioUrlsState.putUrl(item.id, fallbackAudio)
                        }
                    } else {
                        trailerPreviewNegativeCache.add(item.id)
                        trailerPreviewUrlsState.remove(item.id)
                        trailerPreviewAudioUrlsState.remove(item.id)
                    }
                } else {
                    trailerPreviewUrlsState.putUrl(item.id, trailerSource.videoUrl)
                    val audioUrl = trailerSource.audioUrl
                    if (audioUrl.isNullOrBlank()) {
                        trailerPreviewAudioUrlsState.remove(item.id)
                    } else {
                        trailerPreviewAudioUrlsState.putUrl(item.id, audioUrl)
                    }
                }
            }
        } finally {
            trailerPreviewLoadingIds.remove(item.id)
        }
    }
}
