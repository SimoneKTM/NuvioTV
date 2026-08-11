package com.nuvio.tv.ui.screens.anime

import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.CwVideoSummary
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.mergeContinueWatchingItems
import com.nuvio.tv.ui.screens.home.splitUpcomingItems
import com.nuvio.tv.ui.util.parseEpisodeReleaseDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val ANIME_CW_DEBOUNCE_MS = 500L
private const val ANIME_CW_MAX_NEXT_UP_LOOKUPS = 24
private const val ANIME_CW_MAX_NEXT_UP_CONCURRENCY = 3
private const val ANIME_CW_MAX_ENRICHMENT_SHOWS = 40
private const val ANIME_CW_META_TIMEOUT_MS = 6_000L
private const val ANIME_CW_META_NEGATIVE_CACHE_TTL_MS = 5 * 60_000L
private const val ANIME_CW_NEW_SEASON_UNAIRED_WINDOW_DAYS = 7L
private const val ANIME_CW_RELEASE_ALERT_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

private val animeCwMetaCache = ConcurrentHashMap<String, CwMetaSummary?>()
private val animeCwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()

private data class AnimeCwSnapshot(
    val items: List<WatchProgress>,
    val animeBaseUrls: Set<String>,
    val sortMode: ContinueWatchingSortMode,
    val showUnairedNextUp: Boolean,
    val nextUpFromFurthestEpisode: Boolean
)

@OptIn(FlowPreview::class)
internal fun AnimeHomeViewModel.observeAnimeContinueWatching() {
    viewModelScope.launch {
        combine(
            combine(
                watchProgressRepository.allProgress,
                animeAddonRepository.getInstalledAnimeAddons()
            ) { progress, addons ->
                progress to addons
            },
            layoutPreferenceDataStore.continueWatchingSortMode,
            layoutPreferenceDataStore.showUnairedNextUp,
            layoutPreferenceDataStore.nextUpFromFurthestEpisode
        ) { pair, sortMode, showUnaired, nextUpFromFurthest ->
            val (progress, addons) = pair
            AnimeCwSnapshot(
                items = progress,
                animeBaseUrls = addons.mapTo(mutableSetOf()) { normalizeAnimeBaseUrl(it.baseUrl) },
                sortMode = sortMode,
                showUnairedNextUp = showUnaired,
                nextUpFromFurthestEpisode = nextUpFromFurthest
            )
        }.debounce(ANIME_CW_DEBOUNCE_MS).collectLatest { snapshot ->
            buildAnimeContinueWatching(snapshot)
        }
    }
}

private fun normalizeAnimeBaseUrl(raw: String?): String =
    raw?.trim()?.trimEnd('/')?.lowercase(Locale.US).orEmpty()

private fun isAnimeProgress(progress: WatchProgress, animeBaseUrls: Set<String>): Boolean =
    normalizeAnimeBaseUrl(progress.addonBaseUrl) in animeBaseUrls

private fun shouldTreatAsInProgressForAnimeCw(progress: WatchProgress): Boolean {
    if (progress.source == WatchProgress.SOURCE_TRAKT_HISTORY) return false
    if (progress.source == WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS) return false
    return progress.isInProgress()
}

private suspend fun AnimeHomeViewModel.buildAnimeContinueWatching(snapshot: AnimeCwSnapshot) {
    val nowEpochMs = System.currentTimeMillis()
    val inProgress = snapshot.items
        .filter { isAnimeProgress(it, snapshot.animeBaseUrls) && shouldTreatAsInProgressForAnimeCw(it) }
        .sortedByDescending { it.lastWatched }

    val inProgressContentIds = inProgress.mapTo(mutableSetOf()) { it.contentId }
    val seeds = snapshot.items
        .filter { isAnimeProgress(it, snapshot.animeBaseUrls) }
        .filter { it.season != null && it.episode != null && it.season != 0 }
        .filter { watchProgressRepository.shouldUseAsNextUpSeed(it, nowEpochMs) }
        .groupBy { it.contentId }
        .mapNotNull { (_, items) -> chooseAnimePreferredSeed(items, snapshot.nextUpFromFurthestEpisode) }
        .filter { it.contentId !in inProgressContentIds }
        .sortedByDescending { it.lastWatched }
        .take(ANIME_CW_MAX_NEXT_UP_LOOKUPS)

    val rawInProgressItems = inProgress.map { ContinueWatchingItem.InProgress(progress = it) }
    publishAnimeCwItems(rawInProgressItems, emptyList())

    // Enrichment phase: resolve meta for next-up seeds and in-progress shows, then
    // rebuild the row with next-up items and episode thumbnails/descriptions.
    val seedByContentId = seeds.associateBy { it.contentId }
    val showsToResolve = buildList {
        addAll(seedByContentId.keys)
        addAll(inProgressContentIds.take(ANIME_CW_MAX_ENRICHMENT_SHOWS))
    }.distinct()

    coroutineScope {
        val semaphore = Semaphore(ANIME_CW_MAX_NEXT_UP_CONCURRENCY)
        showsToResolve.map { contentId ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    resolveAnimeMeta(contentId, seedByContentId[contentId]?.contentType ?: "series")
                }
            }
        }.awaitAll()
    }

    val nextUpItems = seeds.mapNotNull { seed ->
        buildAnimeNextUpItem(seed, snapshot.showUnairedNextUp)
    }
    val enrichedInProgressItems = inProgress.map { progress ->
        enrichAnimeInProgressItem(progress)
    }
    val combined = mergeContinueWatchingItems(enrichedInProgressItems, nextUpItems, snapshot.sortMode)
    val (mainItems, upcomingItems) = splitUpcomingItems(combined, snapshot.sortMode)
    publishAnimeCwItems(mainItems, upcomingItems)
}

private fun AnimeHomeViewModel.publishAnimeCwItems(
    mainItems: List<ContinueWatchingItem>,
    upcomingItems: List<ContinueWatchingItem>
) {
    _uiState.update { state ->
        val updated = state.copy(
            continueWatchingItems = mainItems,
            upcomingItems = upcomingItems
        )
        if (updated == state) state else updated
    }
}

private fun chooseAnimePreferredSeed(
    items: List<WatchProgress>,
    nextUpFromFurthestEpisode: Boolean
): WatchProgress? {
    if (items.isEmpty()) return null
    return items.maxWithOrNull(
        if (nextUpFromFurthestEpisode) {
            compareBy<WatchProgress>(
                { it.season ?: -1 },
                { it.episode ?: -1 },
                { it.lastWatched }
            )
        } else {
            compareBy<WatchProgress>(
                { it.lastWatched },
                { it.season ?: -1 },
                { it.episode ?: -1 }
            )
        }
    )
}

private suspend fun AnimeHomeViewModel.resolveAnimeMeta(contentId: String, contentType: String): CwMetaSummary? {
    val cacheKey = "$contentType:$contentId"
    animeCwMetaCache[cacheKey]?.let { return it }
    val negativeCachedAt = animeCwMetaNegativeCacheTimestamps[cacheKey]
    if (negativeCachedAt != null &&
        SystemClock.elapsedRealtime() - negativeCachedAt < ANIME_CW_META_NEGATIVE_CACHE_TTL_MS
    ) {
        return null
    }
    animeCwMetaCache.remove(cacheKey)
    animeCwMetaNegativeCacheTimestamps.remove(cacheKey)

    val idCandidates = buildList {
        add(contentId)
        if (contentId.startsWith("tmdb:")) add(contentId.substringAfter(':'))
    }.distinct()
    val typeCandidates = listOf(contentType, "series", "tv").distinct()

    var summary: CwMetaSummary? = null
    outer@ for (type in typeCandidates) {
        for (candidateId in idCandidates) {
            val result = withTimeoutOrNull(ANIME_CW_META_TIMEOUT_MS) {
                metaRepository.getMetaFromAllAddons(
                    type = type,
                    id = candidateId
                ).first { it !is NetworkResult.Loading }
            }
            summary = ((result as? NetworkResult.Success<*>)?.data as? Meta)?.let { it.toAnimeCwSummary() }
            if (summary != null) break@outer
        }
    }

    animeCwMetaCache[cacheKey] = summary
    if (summary == null) {
        animeCwMetaNegativeCacheTimestamps[cacheKey] = SystemClock.elapsedRealtime()
    } else {
        animeCwMetaNegativeCacheTimestamps.remove(cacheKey)
    }
    return summary
}

private fun Meta.toAnimeCwSummary(): CwMetaSummary = CwMetaSummary(
    id = id,
    name = name,
    poster = poster,
    backdropUrl = backdropUrl,
    logo = logo,
    description = description,
    genres = genres,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    language = language,
    country = country,
    videos = videos.map { video ->
        CwVideoSummary(
            id = video.id,
            title = video.title,
            released = video.released,
            thumbnail = video.thumbnail,
            season = video.season,
            episode = video.episode,
            overview = video.overview,
            available = video.available
        )
    }
)

private suspend fun AnimeHomeViewModel.buildAnimeNextUpItem(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): ContinueWatchingItem.NextUp? {
    val meta = resolveAnimeMeta(progress.contentId, progress.contentType) ?: return null
    val nextVideo = resolveAnimeNextUpVideo(progress, meta, showUnairedNextUp) ?: return null
    val nextSeason = nextVideo.season ?: return null
    val nextEpisode = nextVideo.episode ?: return null
    val rawReleased = nextVideo.released?.trim()?.takeIf { it.isNotBlank() }
    val hasAired = hasEpisodeAired(rawReleased)
    val releaseTimestamp = parseEpisodeReleaseInstant(rawReleased)?.toEpochMilli()
    val nowMs = System.currentTimeMillis()
    val isReleaseAlert = hasAired &&
        releaseTimestamp != null &&
        releaseTimestamp > progress.lastWatched &&
        (nowMs - releaseTimestamp) < ANIME_CW_RELEASE_ALERT_WINDOW_MS

    return ContinueWatchingItem.NextUp(
        info = NextUpInfo(
            contentId = progress.contentId,
            contentType = progress.contentType,
            name = progress.name.trim().takeIf { it.isNotEmpty() } ?: meta.name ?: progress.contentId,
            poster = progress.poster ?: meta.poster,
            backdrop = progress.backdrop ?: meta.backdropUrl,
            logo = progress.logo ?: meta.logo,
            videoId = nextVideo.id.takeIf { it.isNotBlank() }
                ?: buildAnimeLightweightVideoId(progress.contentId, nextSeason, nextEpisode),
            season = nextSeason,
            episode = nextEpisode,
            episodeTitle = nextVideo.title?.takeIf { it.isNotBlank() },
            episodeDescription = nextVideo.overview,
            thumbnail = nextVideo.thumbnail,
            released = rawReleased,
            hasAired = hasAired,
            airDateLabel = if (hasAired) null else rawReleased?.let(::parseEpisodeReleaseDate)?.let(::formatAnimeAirDateLabel),
            lastWatched = progress.lastWatched,
            sortTimestamp = if (isReleaseAlert) releaseTimestamp!! else progress.lastWatched,
            releaseTimestamp = releaseTimestamp,
            isReleaseAlert = isReleaseAlert,
            isNewSeasonRelease = isReleaseAlert && progress.season != null && nextSeason != progress.season,
            seedSeason = progress.season,
            seedEpisode = progress.episode
        )
    )
}

private fun resolveAnimeNextUpVideo(
    progress: WatchProgress,
    meta: CwMetaSummary,
    showUnairedNextUp: Boolean
): CwVideoSummary? {
    val episodes = meta.videos
        .filter { video ->
            val season = video.season
            val episode = video.episode
            season != null && episode != null && season != 0
        }
        .sortedWith(compareBy<CwVideoSummary>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE }))
    if (episodes.isEmpty()) return null

    val seedSeason = progress.season ?: return null
    val seedEpisode = progress.episode ?: return null

    var watchedIndex = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }
    if (watchedIndex < 0) {
        val videoId = progress.videoId.takeIf { it.isNotBlank() }
        if (videoId != null) {
            watchedIndex = episodes.indexOfFirst { it.id == videoId }
        }
        if (watchedIndex < 0) return null
    }

    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val watchedSeason = episodes[watchedIndex].season
    return episodes.drop(watchedIndex + 1).firstOrNull { video ->
        val releaseDate = parseEpisodeReleaseDate(video.released)
        if (video.season != watchedSeason) {
            if (releaseDate == null) return@firstOrNull false
            if (!releaseDate.isAfter(todayLocal)) return@firstOrNull true
            if (showUnairedNextUp) {
                val daysUntil = ChronoUnit.DAYS.between(todayLocal, releaseDate)
                if (daysUntil <= ANIME_CW_NEW_SEASON_UNAIRED_WINDOW_DAYS) return@firstOrNull true
            }
            return@firstOrNull false
        }
        val isUnaired = releaseDate?.isAfter(todayLocal) == true
        if (!isUnaired) return@firstOrNull true
        showUnairedNextUp
    }
}

private fun AnimeHomeViewModel.enrichAnimeInProgressItem(progress: WatchProgress): ContinueWatchingItem.InProgress {
    val meta = resolveAnimeMetaSync(progress.contentId, progress.contentType)
    val video = meta?.videos?.firstOrNull { candidate ->
        candidate.id == progress.videoId ||
            (candidate.season == progress.season && candidate.episode == progress.episode)
    }
    return ContinueWatchingItem.InProgress(
        progress = progress,
        episodeThumbnail = video?.thumbnail,
        episodeDescription = video?.overview,
        episodeImdbRating = meta?.imdbRating,
        genres = meta?.genres.orEmpty(),
        releaseInfo = meta?.releaseInfo
    )
}

private fun AnimeHomeViewModel.resolveAnimeMetaSync(contentId: String, contentType: String): CwMetaSummary? =
    animeCwMetaCache["$contentType:$contentId"]

private fun hasEpisodeAired(raw: String?, fallback: Boolean = true): Boolean =
    when (val aired = isEpisodeReleaseAired(raw)) {
        true -> true
        false -> false
        null -> fallback
    }

private fun formatAnimeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == todayLocal.year) "dMMM" else "dMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(releaseDate)
}

private fun buildAnimeLightweightVideoId(contentId: String, season: Int, episode: Int): String =
    "$contentId:$season:$episode"
