package com.nuvio.tv.ui.screens.anime

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.components.brokenImageUrls
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.CwVideoSummary
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.NextUpResolution
import com.nuvio.tv.ui.screens.home.mergeConcurrentContinueWatchingEnrichment
import com.nuvio.tv.ui.screens.home.mergeContinueWatchingItems
import com.nuvio.tv.ui.screens.home.nextUpDismissKey
import com.nuvio.tv.ui.screens.home.reconcileConclusiveNextUpItems
import com.nuvio.tv.ui.screens.home.sortContinueWatchingItems
import com.nuvio.tv.ui.screens.home.splitUpcomingItems
import com.nuvio.tv.ui.screens.home.toCachedNextUpSnapshot
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val ANIME_CW_PROGRESS_DEBOUNCE_MS = 500L
private const val ANIME_CW_MAX_RECENT_PROGRESS_ITEMS = 300

private data class AnimeCwSnapshot(
    val items: List<WatchProgress>,
    val animeBaseUrls: Set<String>,
    val dismissedNextUp: Set<String>,
    val showUnairedNextUp: Boolean,
    val nextUpFromFurthestEpisode: Boolean,
    val continueWatchingSortMode: ContinueWatchingSortMode
)

@OptIn(FlowPreview::class)
internal fun AnimeHomeViewModel.observeAnimeContinueWatching() {
    animeCwPipelineJob?.cancel()
    animeCwPipelineJob = viewModelScope.launch {
        combine(
            combine(
                watchProgressRepository.allProgress,
                animeAddonRepository.getInstalledAnimeAddons()
            ) { progress, addons ->
                progress to addons
            },
            layoutPreferenceDataStore.dismissedNextUpKeys,
            layoutPreferenceDataStore.showUnairedNextUp,
            layoutPreferenceDataStore.nextUpFromFurthestEpisode,
            layoutPreferenceDataStore.continueWatchingSortMode
        ) { pair, dismissedNextUp, showUnaired, nextUpFromFurthest, sortMode ->
            val (progress, addons) = pair
            AnimeCwSnapshot(
                items = progress,
                animeBaseUrls = addons.mapTo(mutableSetOf()) { normalizeAnimeBaseUrl(it.baseUrl) },
                dismissedNextUp = dismissedNextUp,
                showUnairedNextUp = showUnaired,
                nextUpFromFurthestEpisode = nextUpFromFurthest,
                continueWatchingSortMode = sortMode
            )
        }.debounce(ANIME_CW_PROGRESS_DEBOUNCE_MS).collectLatest { snapshot ->
            buildAnimeContinueWatching(snapshot)
        }
    }
}

private suspend fun AnimeHomeViewModel.buildAnimeContinueWatching(snapshot: AnimeCwSnapshot) {
    val recentItems = snapshot.items
        .asSequence()
        .filter { isAnimeProgress(it, snapshot.animeBaseUrls) }
        .sortedByDescending { it.lastWatched }
        .take(ANIME_CW_MAX_RECENT_PROGRESS_ITEMS)
        .toList()

    val animeSeeds = deriveAnimeNextUpSeeds(recentItems, snapshot.nextUpFromFurthestEpisode)
    val activeSeedContentIds = animeSeeds.mapTo(mutableSetOf()) { it.contentId }

    // Evict in-memory next-up caches for series that lost all seeds
    // (e.g. user unmarked all episodes as watched).
    synchronized(animeDiscoveredOlderNextUpItems) {
        animeDiscoveredOlderNextUpItems.removeAll { it.info.contentId !in activeSeedContentIds }
    }
    synchronized(animeCwEnrichedNextUpOverlay) {
        animeCwEnrichedNextUpOverlay.keys.removeAll { it !in activeSeedContentIds }
    }

    // Load cached CW snapshots for instant render before enrichment resolves
    val (cachedNextUp, cachedInProgress) = coroutineScope {
        val nextUpDeferred = async(Dispatchers.IO) {
            runCatching { animeCwEnrichmentCache.getNextUpSnapshot() }.getOrDefault(emptyList())
        }
        val inProgressDeferred = async(Dispatchers.IO) {
            runCatching { animeCwEnrichmentCache.getInProgressSnapshot() }.getOrDefault(emptyList())
        }
        nextUpDeferred.await() to inProgressDeferred.await()
    }
    // Build enrichment lookup from cached snapshots (replaces old CwEnrichmentEntry)
    val cachedEnrichmentFromInProgress = cachedInProgress.associateBy { it.contentId }
    val cachedEnrichmentFromNextUp = cachedNextUp.associateBy { it.contentId }

    // Seed the in-memory enrichment overlay from disk cache on first cycle
    // so that fresh builds use enriched titles/thumbnails from the start.
    if (animeCwEnrichedNextUpOverlay.isEmpty() && cachedNextUp.isNotEmpty()) {
        cachedNextUp.forEach { cached ->
            animeCwEnrichedNextUpOverlay[cached.contentId] = NextUpInfo(
                contentId = cached.contentId,
                contentType = cached.contentType,
                name = cached.name,
                poster = cached.poster,
                backdrop = cached.backdrop,
                logo = cached.logo,
                videoId = cached.videoId,
                season = cached.season,
                episode = cached.episode,
                episodeTitle = cached.episodeTitle,
                episodeDescription = cached.episodeDescription,
                thumbnail = cached.thumbnail,
                released = cached.released,
                hasAired = cached.hasAired,
                airDateLabel = cached.airDateLabel,
                lastWatched = cached.lastWatched,
                imdbRating = cached.imdbRating,
                genres = cached.genres,
                releaseInfo = cached.releaseInfo,
                sortTimestamp = cached.sortTimestamp,
                releaseTimestamp = cached.releaseTimestamp,
                isReleaseAlert = cached.isReleaseAlert,
                isNewSeasonRelease = cached.isNewSeasonRelease,
                seedSeason = cached.seedSeason,
                seedEpisode = cached.seedEpisode,
                contentLanguage = cached.contentLanguage
            )
        }
    }

    val liveInProgress = deduplicateInProgress(
        recentItems.filter { shouldTreatAsInProgressForContinueWatching(it) }
    )
    val inProgressOnly = buildList {
        if (liveInProgress.isNotEmpty()) {
            liveInProgress.forEach { progress ->
                val cached = cachedEnrichmentFromInProgress[progress.contentId]
                val displayProgress = if (
                    cached != null &&
                    (cached.backdrop != null || cached.poster != null || cached.logo != null || cached.name.isNotBlank())
                ) {
                    val sameEpisode = cached.season == progress.season && cached.episode == progress.episode
                    progress.copy(
                        backdrop = cached.backdrop ?: progress.backdrop,
                        poster = cached.poster ?: progress.poster,
                        logo = cached.logo ?: progress.logo,
                        name = cached.name.takeIf { it.isNotBlank() } ?: progress.name,
                        episodeTitle = if (sameEpisode) (cached.episodeTitle ?: progress.episodeTitle) else progress.episodeTitle,
                        videoId = if (sameEpisode) (cached.videoId.takeIf { it.isNotBlank() } ?: progress.videoId) else progress.videoId
                    )
                } else {
                    progress
                }
                add(
                    ContinueWatchingItem.InProgress(
                        progress = displayProgress,
                        episodeThumbnail = cached?.episodeThumbnail,
                        episodeDescription = cached?.episodeDescription,
                        episodeImdbRating = cached?.episodeImdbRating,
                        genres = cached?.genres ?: emptyList(),
                        releaseInfo = cached?.releaseInfo,
                        contentLanguage = cached?.contentLanguage
                    )
                )
            }
        }
        if (
            animeShouldRestoreCachedInProgress(
                hasLiveInProgress = liveInProgress.isNotEmpty(),
                hasCachedInProgress = cachedInProgress.isNotEmpty(),
                hasProviderItems = recentItems.isNotEmpty()
            )
        ) {
            cachedInProgress.forEach { cached ->
                add(
                    ContinueWatchingItem.InProgress(
                        progress = WatchProgress(
                            contentId = cached.contentId,
                            contentType = cached.contentType,
                            name = cached.name,
                            poster = cached.poster,
                            backdrop = cached.backdrop,
                            logo = cached.logo,
                            videoId = cached.videoId,
                            season = cached.season,
                            episode = cached.episode,
                            episodeTitle = cached.episodeTitle,
                            position = cached.position,
                            duration = cached.duration,
                            lastWatched = cached.lastWatched,
                            progressPercent = cached.progressPercent
                        ),
                        episodeThumbnail = cached.episodeThumbnail,
                        episodeDescription = cached.episodeDescription,
                        episodeImdbRating = cached.episodeImdbRating,
                        genres = cached.genres,
                        releaseInfo = cached.releaseInfo
                    )
                )
            }
        }
    }

    // Render in-progress items + cached next-up immediately
    val currentSeedByContentId = animeSeeds
        .filter { it.season != null && it.episode != null }
        .associateBy({ it.contentId }, { (it.season!! to it.episode!!) })
    val cachedNextUpItems = cachedNextUp.mapNotNull { cached ->
        // Skip if this show is already in-progress (suppression)
        if (inProgressOnly.any { it.progress.contentId == cached.contentId }) return@mapNotNull null
        // Skip dismissed items
        if (nextUpDismissKey(cached.contentId, cached.seedSeason, cached.seedEpisode) in snapshot.dismissedNextUp) {
            return@mapNotNull null
        }
        // Recalculate release alert flags from persisted timestamps so that
        // badges appear correctly even when the cache was written before the
        // new season aired (fixes stale isNewSeasonRelease/isReleaseAlert/hasAired).
        val (freshHasAired, freshIsReleaseAlert, freshIsNewSeasonRelease) = recalculateAnimeCachedReleaseBadge(cached)
        // Respect "show unaired" setting (use recalculated hasAired)
        if (!freshHasAired && !snapshot.showUnairedNextUp) return@mapNotNull null
        // Drop if the series no longer has any watched-episode seeds
        if (cached.contentId !in activeSeedContentIds) return@mapNotNull null
        val currentSeed = currentSeedByContentId[cached.contentId]
        if (currentSeed != null && cached.seedSeason != null && cached.seedEpisode != null) {
            val (curSeason, curEpisode) = currentSeed
            val seedAdvanced = curSeason > cached.seedSeason ||
                (curSeason == cached.seedSeason && curEpisode > cached.seedEpisode)
            if (seedAdvanced) return@mapNotNull null
        }
        ContinueWatchingItem.NextUp(
            info = NextUpInfo(
                contentId = cached.contentId,
                contentType = cached.contentType,
                name = cached.name,
                poster = cached.poster,
                backdrop = cached.backdrop,
                logo = cached.logo,
                videoId = cached.videoId,
                season = cached.season,
                episode = cached.episode,
                episodeTitle = cached.episodeTitle,
                episodeDescription = cached.episodeDescription,
                thumbnail = cached.thumbnail,
                released = cached.released,
                hasAired = freshHasAired,
                airDateLabel = cached.airDateLabel,
                lastWatched = cached.lastWatched,
                imdbRating = cached.imdbRating,
                genres = cached.genres,
                releaseInfo = cached.releaseInfo,
                sortTimestamp = if (freshIsReleaseAlert && cached.releaseTimestamp != null) cached.releaseTimestamp else cached.lastWatched,
                releaseTimestamp = cached.releaseTimestamp,
                isReleaseAlert = freshIsReleaseAlert,
                isNewSeasonRelease = freshIsNewSeasonRelease,
                seedSeason = cached.seedSeason,
                seedEpisode = cached.seedEpisode
            )
        )
    }

    if (inProgressOnly.isNotEmpty() || cachedNextUpItems.isNotEmpty()) {
        val initialItems = applyAnimeContinueWatchingEnrichmentOverlay(
            mergeContinueWatchingItems(
                inProgressItems = inProgressOnly,
                nextUpItems = cachedNextUpItems,
                mode = snapshot.continueWatchingSortMode
            ),
            sortMode = snapshot.continueWatchingSortMode
        )
        val (mainItems, upcomingOnly) = splitUpcomingItems(initialItems, snapshot.continueWatchingSortMode)
        publishAnimeCwItems(mainItems, upcomingOnly)
        // Persist in-progress snapshot early so force-close doesn't lose items.
        if (inProgressOnly.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ipSnap = inProgressOnly.map { item ->
                    CachedInProgressItem(
                        contentId = item.progress.contentId,
                        contentType = item.progress.contentType,
                        name = item.progress.name,
                        poster = item.progress.poster,
                        backdrop = item.progress.backdrop,
                        logo = item.progress.logo,
                        videoId = item.progress.videoId,
                        season = item.progress.season,
                        episode = item.progress.episode,
                        episodeTitle = item.progress.episodeTitle,
                        position = item.progress.position,
                        duration = item.progress.duration,
                        lastWatched = item.progress.lastWatched,
                        progressPercent = item.progress.progressPercent,
                        episodeThumbnail = item.episodeThumbnail?.takeIf { it !in brokenImageUrls },
                        episodeDescription = item.episodeDescription,
                        episodeImdbRating = item.episodeImdbRating,
                        genres = item.genres,
                        releaseInfo = item.releaseInfo,
                        contentLanguage = item.contentLanguage
                    )
                }
                runCatching { animeCwEnrichmentCache.saveInProgressSnapshot(ipSnap) }
            }
        }
    }

    coroutineScope {
    // --- Build lightweight next-up items (fino a ANIME_CW_MAX_NEXT_UP_LOOKUPS seeds) ---
    val publishedPartialNextUpCount = AtomicInteger(0)
    val partialPublishMutex = Mutex()
    val nextUpItems = buildAnimeNextUpItems(
        allProgress = recentItems,
        animeSeeds = animeSeeds,
        inProgressItems = inProgressOnly,
        dismissedNextUp = snapshot.dismissedNextUp,
        showUnairedNextUp = snapshot.showUnairedNextUp,
        nextUpFromFurthestEpisode = snapshot.nextUpFromFurthestEpisode,
        onPartialUpdate = { partialNextUpItems ->
            partialPublishMutex.withLock {
                val partialCount = partialNextUpItems.size
                if (partialCount > publishedPartialNextUpCount.get()) {
                    publishedPartialNextUpCount.set(partialCount)
                    val freshIds = partialNextUpItems.map { it.info.contentId }.toSet()
                    val cachedPartialNextUp = partialNextUpItems.map { nextUp ->
                        val cached = cachedEnrichmentFromNextUp[nextUp.info.contentId]
                        if (cached != null && cached.season == nextUp.info.season && cached.episode == nextUp.info.episode) {
                            nextUp.copy(info = nextUp.info.copy(
                                thumbnail = cached.thumbnail ?: nextUp.info.thumbnail,
                                backdrop = cached.backdrop ?: nextUp.info.backdrop,
                                poster = cached.poster ?: nextUp.info.poster,
                                logo = cached.logo ?: nextUp.info.logo,
                                name = cached.name.takeIf { it.isNotBlank() } ?: nextUp.info.name,
                                contentLanguage = cached.contentLanguage ?: nextUp.info.contentLanguage
                            ))
                        } else nextUp
                    }
                    // Keep cached next-up items for series not yet processed
                    // by the fresh pipeline so they don't disappear mid-build.
                    val retainedCached = cachedNextUpItems.filter {
                        it.info.contentId !in freshIds
                    }
                    val partialItems = applyAnimeContinueWatchingEnrichmentOverlay(
                        mergeContinueWatchingItems(
                            inProgressItems = inProgressOnly,
                            nextUpItems = cachedPartialNextUp + retainedCached,
                            mode = snapshot.continueWatchingSortMode
                        ),
                        sortMode = snapshot.continueWatchingSortMode
                    )
                    val (partialMain, partialUpcoming) = splitUpcomingItems(partialItems, snapshot.continueWatchingSortMode)
                    publishAnimeCwItems(partialMain, partialUpcoming)
                }
            }
        }
    )

    // --- Anime next-up injection per seeds oltre i primi 32 ---
    // Risoluzione asincrona di serie con seed più vecchi e iniezione degli
    // episodi successivi nella lista (con pubblicazioni parziali ogni 3 item).
    val conclusivelyProcessedOlderContentIds = ConcurrentHashMap.newKeySet<String>()
    val resolvedOlderNextUpContentIds = ConcurrentHashMap.newKeySet<String>()
    val allSeedContentIds = animeSeeds
        .filter { isSeriesTypeCW(it.contentType) && it.season != null && it.episode != null }
        .map { it.contentId }
        .toSet()
    val processedContentIds = synchronized(animeCwLastProcessedNextUpContentIds) {
        animeCwLastProcessedNextUpContentIds.toSet()
    }
    val olderSeedContentIds = (allSeedContentIds - processedContentIds).filter { contentId ->
        // Allow series in disk cache to be re-resolved only when not already
        // resolved in this session (resolution cache empty).
        synchronized(animeCwNextUpResolutionCache) {
            animeCwNextUpResolutionCache.keys.none { it.startsWith("$contentId|") }
        }
    }.toSet()
    if (olderSeedContentIds.isNotEmpty()) {
        val olderSeeds = animeSeeds
            .filter { it.contentId in olderSeedContentIds }
            .filter { isSeriesTypeCW(it.contentType) && it.season != null && it.episode != null && it.season != 0 }
            .filter { shouldUseAsCompletedSeed(it) }
            .groupBy { it.contentId }
            .mapNotNull { (_, items) -> choosePreferredNextUpSeed(items, snapshot.nextUpFromFurthestEpisode) }
        if (olderSeeds.isNotEmpty()) {
            launch(Dispatchers.IO) {
                // Process sequentially with yielding to avoid CPU/GC spikes.
                // Emit partial updates every few resolved items so user sees
                // new CW entries appearing progressively.
                val discoveredNextUpItems = mutableListOf<ContinueWatchingItem.NextUp>()
                var resolvedSinceLastEmit = 0
                for (seed in olderSeeds) {
                    val item = buildAnimeNextUpItem(
                        progress = seed,
                        showUnairedNextUp = snapshot.showUnairedNextUp
                    )
                    if (item != null) {
                        conclusivelyProcessedOlderContentIds += seed.contentId
                        resolvedOlderNextUpContentIds += seed.contentId
                        discoveredNextUpItems.add(item)
                        resolvedSinceLastEmit++
                        if (resolvedSinceLastEmit >= 3) {
                            resolvedSinceLastEmit = 0
                            applyConclusiveAnimeOlderNextUpResults(
                                resolvedItems = discoveredNextUpItems.toList(),
                                conclusivelyProcessedContentIds = conclusivelyProcessedOlderContentIds.toSet(),
                                dismissedNextUpKeys = snapshot.dismissedNextUp,
                                sortMode = snapshot.continueWatchingSortMode,
                                persistSnapshot = false
                            )
                        }
                    } else {
                        // No next-up — mark as processed ONLY if meta was actually
                        // resolved (confirming no next episode). If meta was
                        // unavailable (network error), skip marking to avoid
                        // incorrectly removing the series from Continue Watching.
                        val metaWasResolved = synchronized(animeCwMetaCache) {
                            animeCwMetaCache["${seed.contentType}:${seed.contentId}"]
                                ?: animeCwMetaCache["series:${seed.contentId}"]
                                ?: animeCwMetaCache["tv:${seed.contentId}"]
                        } != null
                        if (metaWasResolved) {
                            conclusivelyProcessedOlderContentIds += seed.contentId
                        }
                    }
                    kotlinx.coroutines.yield()
                }
                if (conclusivelyProcessedOlderContentIds.isNotEmpty()) {
                    applyConclusiveAnimeOlderNextUpResults(
                        resolvedItems = discoveredNextUpItems.toList(),
                        conclusivelyProcessedContentIds = conclusivelyProcessedOlderContentIds.toSet(),
                        dismissedNextUpKeys = snapshot.dismissedNextUp,
                        sortMode = snapshot.continueWatchingSortMode,
                        persistSnapshot = true
                    )
                }
            }
        }
    }

    // --- Merge finale e publish ---
    // Include previously discovered older next-up items so they survive collectLatest restarts.
    val persistedOlderItems = synchronized(animeDiscoveredOlderNextUpItems) {
        animeDiscoveredOlderNextUpItems.toList()
    }
    // Preserve cached next-up items from disk until async inject re-verifies them.
    // Drop items whose series no longer has any watched-episode seeds.
    val cachedOlderNextUp = cachedNextUp
        .filter { it.contentId in activeSeedContentIds }
        .map { cached ->
            val (freshHasAired, freshIsReleaseAlert, freshIsNewSeasonRelease) = recalculateAnimeCachedReleaseBadge(cached)
            ContinueWatchingItem.NextUp(
                info = NextUpInfo(
                    contentId = cached.contentId,
                    contentType = cached.contentType,
                    name = cached.name,
                    poster = cached.poster,
                    backdrop = cached.backdrop,
                    logo = cached.logo,
                    videoId = cached.videoId,
                    season = cached.season,
                    episode = cached.episode,
                    episodeTitle = cached.episodeTitle,
                    episodeDescription = cached.episodeDescription,
                    thumbnail = cached.thumbnail,
                    released = cached.released,
                    hasAired = freshHasAired,
                    airDateLabel = cached.airDateLabel,
                    lastWatched = cached.lastWatched,
                    imdbRating = cached.imdbRating,
                    genres = cached.genres,
                    releaseInfo = cached.releaseInfo,
                    sortTimestamp = if (freshIsReleaseAlert && cached.releaseTimestamp != null) cached.releaseTimestamp else cached.lastWatched,
                    releaseTimestamp = cached.releaseTimestamp,
                    isReleaseAlert = freshIsReleaseAlert,
                    isNewSeasonRelease = freshIsNewSeasonRelease,
                    seedSeason = cached.seedSeason,
                    seedEpisode = cached.seedEpisode
                )
            )
        }
    val recentIds = nextUpItems.map { it.info.contentId }.toSet()
    val inProgressIds = inProgressOnly.map { it.progress.contentId }.toSet()
    // Exclude cached older items for series that the fresh pipeline evaluated
    // but didn't produce a next-up for (e.g. fully watched series).
    val rejectedByFreshPipeline = synchronized(animeCwLastProcessedNextUpContentIds) {
        animeCwLastProcessedNextUpContentIds.toSet()
    } - recentIds
    val olderToInclude = (persistedOlderItems + cachedOlderNextUp)
        .distinctBy { it.info.contentId }
        .filter {
            val isCachedFromDisk = cachedOlderNextUp.any { c -> c.info.contentId == it.info.contentId }
            val pass =
                (it.info.contentId in activeSeedContentIds || isCachedFromDisk) &&
                    it.info.contentId !in recentIds &&
                    it.info.contentId !in inProgressIds &&
                    (
                        it.info.contentId !in conclusivelyProcessedOlderContentIds ||
                            it.info.contentId in resolvedOlderNextUpContentIds
                    ) &&
                    it.info.contentId !in rejectedByFreshPipeline &&
                    // Respect "show unaired" setting for all items including cached.
                    (it.info.hasAired || snapshot.showUnairedNextUp) &&
                    nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in snapshot.dismissedNextUp &&
                    !watchProgressRepository.isDroppedShow(it.info.contentId)
            pass
        }
    val allNextUpItems = nextUpItems + olderToInclude
    val freshContentIds = allNextUpItems.map { it.info.contentId }.toSet()
    val retainedFromCache = cachedNextUpItems.filter {
        it.info.contentId !in freshContentIds &&
            (
                it.info.contentId !in conclusivelyProcessedOlderContentIds ||
                    it.info.contentId in resolvedOlderNextUpContentIds
            ) &&
            it.info.contentId !in rejectedByFreshPipeline &&
            nextUpDismissKey(it.info.contentId, it.info.seedSeason, it.info.seedEpisode) !in snapshot.dismissedNextUp
    }
    val finalNextUpItems = allNextUpItems + retainedFromCache
    val normalItems = applyAnimeContinueWatchingEnrichmentOverlay(
        mergeContinueWatchingItems(
            inProgressItems = inProgressOnly,
            nextUpItems = finalNextUpItems.map { nextUp ->
                val cached = cachedEnrichmentFromNextUp[nextUp.info.contentId]
                if (cached != null && cached.season == nextUp.info.season && cached.episode == nextUp.info.episode) {
                    nextUp.copy(info = nextUp.info.copy(
                        thumbnail = cached.thumbnail ?: nextUp.info.thumbnail,
                        backdrop = cached.backdrop ?: nextUp.info.backdrop,
                        poster = cached.poster ?: nextUp.info.poster,
                        logo = cached.logo ?: nextUp.info.logo,
                        name = cached.name.takeIf { it.isNotBlank() } ?: nextUp.info.name,
                        episodeDescription = cached.episodeDescription ?: nextUp.info.episodeDescription,
                        imdbRating = cached.imdbRating ?: nextUp.info.imdbRating,
                        genres = cached.genres.ifEmpty { nextUp.info.genres },
                        releaseInfo = cached.releaseInfo ?: nextUp.info.releaseInfo,
                        contentLanguage = cached.contentLanguage ?: nextUp.info.contentLanguage
                    ))
                } else nextUp
            },
            mode = snapshot.continueWatchingSortMode
        ),
        sortMode = snapshot.continueWatchingSortMode
    )
    val (normalMain, normalUpcoming) = splitUpcomingItems(normalItems, snapshot.continueWatchingSortMode)
    publishAnimeCwItems(normalMain, normalUpcoming)

    // Save lightweight CW snapshot to disk immediately so cache stays fresh
    // even if enrichment is cancelled by collectLatest.
    persistAnimeCwSnapshotsFromUi()

    // Rich metadata only runs after the final lightweight CW list is visible.
    enrichAnimeVisibleContinueWatchingItems(
        finalItems = normalItems,
        sortMode = snapshot.continueWatchingSortMode
    )
    }
}

private fun animeShouldRestoreCachedInProgress(
    hasLiveInProgress: Boolean,
    hasCachedInProgress: Boolean,
    hasProviderItems: Boolean
): Boolean =
    !hasLiveInProgress &&
        hasCachedInProgress &&
        !hasProviderItems

private fun recalculateAnimeCachedReleaseBadge(cached: CachedNextUpItem): Triple<Boolean, Boolean, Boolean> {
    val releaseTimestamp = cached.releaseTimestamp
    val nowMs = System.currentTimeMillis()
    val hasAired = if (releaseTimestamp != null) {
        nowMs >= releaseTimestamp
    } else {
        cached.hasAired
    }
    val sixtyDaysMs = 60L * 24 * 60 * 60 * 1000
    val isReleaseAlert = hasAired &&
        releaseTimestamp != null &&
        releaseTimestamp > cached.lastWatched &&
        (nowMs - releaseTimestamp) < sixtyDaysMs
    val isNewSeasonRelease = isReleaseAlert &&
        cached.seedSeason != null &&
        cached.season != cached.seedSeason
    return Triple(hasAired, isReleaseAlert, isNewSeasonRelease)
}

private suspend fun AnimeHomeViewModel.applyAnimeContinueWatchingEnrichmentOverlay(
    items: List<ContinueWatchingItem>,
    sortMode: ContinueWatchingSortMode
): List<ContinueWatchingItem> {
    if (animeCwEnrichedNextUpOverlay.isEmpty() && animeCwEnrichedInProgressOverlay.isEmpty()) return items
    return withContext(Dispatchers.Default) {
        var sortChanged = false
        val mapped = items.map { item ->
            when (item) {
                is ContinueWatchingItem.NextUp -> {
                    val overlay = animeCwEnrichedNextUpOverlay[item.info.contentId] ?: return@map item
                    if (overlay.season != item.info.season || overlay.episode != item.info.episode) {
                        animeCwEnrichedNextUpOverlay.remove(item.info.contentId)
                        return@map item
                    }
                    if (overlay.sortTimestamp != item.info.sortTimestamp) sortChanged = true
                    item.copy(info = item.info.copy(
                        name = overlay.name.takeIf { it.isNotBlank() } ?: item.info.name,
                        episodeTitle = overlay.episodeTitle ?: item.info.episodeTitle,
                        episodeDescription = overlay.episodeDescription ?: item.info.episodeDescription,
                        thumbnail = overlay.thumbnail ?: item.info.thumbnail,
                        poster = overlay.poster ?: item.info.poster,
                        backdrop = overlay.backdrop ?: item.info.backdrop,
                        logo = overlay.logo ?: item.info.logo,
                        imdbRating = overlay.imdbRating ?: item.info.imdbRating,
                        genres = overlay.genres.ifEmpty { item.info.genres },
                        releaseInfo = overlay.releaseInfo ?: item.info.releaseInfo,
                        sortTimestamp = overlay.sortTimestamp,
                        isReleaseAlert = overlay.isReleaseAlert,
                        isNewSeasonRelease = overlay.isNewSeasonRelease,
                        hasAired = overlay.hasAired,
                        airDateLabel = overlay.airDateLabel ?: item.info.airDateLabel,
                        releaseTimestamp = overlay.releaseTimestamp ?: item.info.releaseTimestamp,
                        contentLanguage = overlay.contentLanguage ?: item.info.contentLanguage
                    ))
                }
                is ContinueWatchingItem.InProgress -> {
                    val overlay = animeCwEnrichedInProgressOverlay[item.progress.contentId] ?: return@map item
                    if (overlay.progress.season != item.progress.season || overlay.progress.episode != item.progress.episode) {
                        return@map item
                    }
                    item.copy(
                        progress = item.progress.copy(
                            name = overlay.progress.name.takeIf { it.isNotBlank() } ?: item.progress.name,
                            poster = overlay.progress.poster ?: item.progress.poster,
                            backdrop = overlay.progress.backdrop ?: item.progress.backdrop,
                            logo = overlay.progress.logo ?: item.progress.logo,
                            episodeTitle = overlay.progress.episodeTitle ?: item.progress.episodeTitle
                        ),
                        episodeThumbnail = overlay.episodeThumbnail ?: item.episodeThumbnail,
                        episodeDescription = overlay.episodeDescription ?: item.episodeDescription,
                        episodeImdbRating = overlay.episodeImdbRating ?: item.episodeImdbRating,
                        genres = overlay.genres.ifEmpty { item.genres },
                        releaseInfo = overlay.releaseInfo ?: item.releaseInfo,
                        contentLanguage = overlay.contentLanguage ?: item.contentLanguage
                    )
                }
            }
        }
        if (sortChanged) sortContinueWatchingItems(mapped, sortMode) else mapped
    }
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

private fun AnimeHomeViewModel.deriveAnimeNextUpSeeds(
    recentItems: List<WatchProgress>,
    nextUpFromFurthestEpisode: Boolean
): List<WatchProgress> {
    val grouped = recentItems
        .filter { item ->
            isSeriesTypeCW(item.contentType) &&
                item.season != null &&
                item.episode != null &&
                item.season != 0 &&
                !isMalformedNextUpSeedContentId(item.contentId) &&
                item.isCompleted()
        }
        .groupBy { it.contentId }
    return grouped.values
        .mapNotNull { group -> choosePreferredNextUpSeed(group, nextUpFromFurthestEpisode) }
        .sortedByDescending { it.lastWatched }
}

private fun normalizeAnimeBaseUrl(raw: String?): String =
    raw?.trim()?.trimEnd('/')?.lowercase(Locale.US).orEmpty()

private fun isAnimeProgress(progress: WatchProgress, animeBaseUrls: Set<String>): Boolean =
    normalizeAnimeBaseUrl(progress.addonBaseUrl) in animeBaseUrls

private fun isSeriesTypeCW(type: String?): Boolean {
    return type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)
}

private fun deduplicateInProgress(items: List<WatchProgress>): List<WatchProgress> {
    val (series, nonSeries) = items.partition { isSeriesTypeCW(it.contentType) }
    val latestPerShow = series
        .sortedByDescending { it.lastWatched }
        .distinctBy { it.contentId }
    return (nonSeries + latestPerShow).sortedByDescending { it.lastWatched }
}

private fun shouldTreatAsInProgressForContinueWatching(progress: WatchProgress): Boolean {
    if (progress.isCompleted()) return false
    if (progress.isInProgress() && progress.progressPercentage >= 0.02f) return true

    val hasStartedPlayback = progress.position > 0L ||
        progress.progressPercent?.let { it > 0f } == true
    return hasStartedPlayback &&
        progress.source != WatchProgress.SOURCE_TRAKT_HISTORY &&
        progress.source != WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS
}

private fun isMalformedNextUpSeedContentId(contentId: String?): Boolean {
    val trimmed = contentId?.trim().orEmpty()
    if (trimmed.isEmpty()) return true
    return when (trimmed.lowercase(Locale.US)) {
        "tmdb", "imdb", "trakt", "tmdb:", "imdb:", "trakt:" -> true
        else -> false
    }
}

private fun nextUpSeedSourceRank(progress: WatchProgress): Int {
    return when (progress.source) {
        WatchProgress.SOURCE_TRAKT_PLAYBACK -> 0
        WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> 0
        WatchProgress.SOURCE_TRAKT_HISTORY -> 1
        WatchProgress.SOURCE_LOCAL -> 2
        else -> 4
    }
}

private fun choosePreferredNextUpSeed(
    items: List<WatchProgress>,
    nextUpFromFurthestEpisode: Boolean = true
): WatchProgress? {
    if (items.isEmpty()) return null
    val bestRank = items.minOf(::nextUpSeedSourceRank)
    return items
        .asSequence()
        .filter { nextUpSeedSourceRank(it) == bestRank }
        .maxWithOrNull(
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

// --- Risoluzione meta e next-up (specchio della pipeline home, senza parti Trakt/TMDB) ---

private const val ANIME_CW_MAX_NEXT_UP_LOOKUPS = 32
private const val ANIME_CW_MAX_NEXT_UP_CONCURRENCY = 4
private const val ANIME_CW_META_NEGATIVE_CACHE_TTL_MS = 5 * 60_000L
private const val ANIME_CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS = 7

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
    videos = videos.map { v ->
        CwVideoSummary(
            id = v.id,
            title = v.title,
            released = v.released,
            thumbnail = v.thumbnail,
            season = v.season,
            episode = v.episode,
            overview = v.overview,
            available = v.available
        )
    }
)

private data class AnimeNextUpReleaseState(
    val sortTimestamp: Long,
    val releaseTimestamp: Long?,
    val isReleaseAlert: Boolean,
    val isNewSeasonRelease: Boolean
)

private fun resolveAnimeNextUpReleaseState(
    seedProgress: WatchProgress,
    nextSeason: Int,
    nextReleased: String?,
    hasAired: Boolean
): AnimeNextUpReleaseState {
    val releaseTimestamp = parseEpisodeReleaseInstant(nextReleased)?.toEpochMilli()
    val nowMs = System.currentTimeMillis()
    val sixtyDaysMs = 60L * 24 * 60 * 60 * 1000
    val isReleaseAlert = hasAired &&
        releaseTimestamp != null &&
        releaseTimestamp > seedProgress.lastWatched &&
        // Suppress release alerts for episodes that aired more than 60 days ago —
        // the user likely abandoned the show.
        (nowMs - releaseTimestamp) < sixtyDaysMs

    return AnimeNextUpReleaseState(
        sortTimestamp = if (isReleaseAlert) releaseTimestamp!! else seedProgress.lastWatched,
        releaseTimestamp = releaseTimestamp,
        isReleaseAlert = isReleaseAlert,
        isNewSeasonRelease = isReleaseAlert && seedProgress.season != null && nextSeason != seedProgress.season
    )
}

private fun buildAnimeLightweightEpisodeVideoId(
    contentId: String,
    season: Int,
    episode: Int
): String = "$contentId:$season:$episode"

private fun buildAnimeNextUpSeedCacheKey(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): String {
    return buildString {
        append(progress.contentId.trim())
        append("|")
        append(progress.season ?: -1)
        append("|")
        append(progress.episode ?: -1)
        append("|unaired=")
        append(showUnairedNextUp)
    }
}

private fun hasAnimeEpisodeAired(raw: String?, fallback: Boolean = true): Boolean {
    return isEpisodeReleaseAired(raw) ?: fallback
}

private fun formatAnimeEpisodeAirDateLabel(releaseDate: LocalDate): String {
    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == todayLocal.year) "dMMM" else "dMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(releaseDate)
}

private fun resolveAnimeNextUpVideoFromMeta(
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

    val seedSeason = progress.season
    val seedEpisode = progress.episode
    if (seedSeason == null || seedEpisode == null) return null

    var watchedIndex = episodes.indexOfFirst { it.season == seedSeason && it.episode == seedEpisode }

    // Fallback: if the seed wasn't found by season+episode
    if (watchedIndex < 0) {
        val videoId = progress.videoId.takeIf { it.isNotBlank() }
        if (videoId != null) {
            watchedIndex = episodes.indexOfFirst { it.id == videoId }
        }
        if (watchedIndex < 0) {
            // Index-based fallback: treat the seed as the Nth episode overall
            val addonSeasons = episodes.mapTo(mutableSetOf()) { it.season }
            if (seedSeason == 1 && addonSeasons.size > 1 && seedEpisode > 0) {
                val globalIndex = seedEpisode - 1 // 0-based
                if (globalIndex in episodes.indices) {
                    watchedIndex = globalIndex
                }
            }
        }
    }

    if (watchedIndex < 0) return null

    val todayLocal = LocalDate.now(ZoneId.systemDefault())
    val watchedEpisodeSeason = episodes[watchedIndex].season
    val nextVideo = episodes.drop(watchedIndex + 1).firstOrNull { video ->
        val releaseDate = parseEpisodeReleaseDate(video.released)
        val isSeasonRollover = video.season != watchedEpisodeSeason
        if (isSeasonRollover) {
            if (releaseDate == null) return@firstOrNull false
            if (!releaseDate.isAfter(todayLocal)) return@firstOrNull true
            // Show unaired next-season episodes within 7-day window
            if (showUnairedNextUp) {
                val daysUntil = java.time.temporal.ChronoUnit.DAYS.between(todayLocal, releaseDate)
                if (daysUntil <= ANIME_CW_NEXT_UP_NEW_SEASON_UNAIRED_WINDOW_DAYS) {
                    return@firstOrNull true
                }
            }
            return@firstOrNull false
        }

        val isUnaired = releaseDate?.isAfter(todayLocal) == true
        if (!isUnaired) return@firstOrNull true
        if (!showUnairedNextUp) return@firstOrNull false
        true
    }

    return nextVideo
}

private suspend fun AnimeHomeViewModel.resolveAnimeMetaForProgress(
    progress: WatchProgress
): CwMetaSummary? {
    val cacheKey = "${progress.contentType}:${progress.contentId}"
    synchronized(animeCwMetaCache) {
        if (animeCwMetaCache.containsKey(cacheKey)) {
            val cached = animeCwMetaCache[cacheKey]
            if (cached != null) return cached
            val negativeCachedAt = animeCwMetaNegativeCacheTimestamps[cacheKey]
            if (negativeCachedAt != null &&
                System.currentTimeMillis() - negativeCachedAt < ANIME_CW_META_NEGATIVE_CACHE_TTL_MS
            ) {
                return null
            }
            animeCwMetaCache.remove(cacheKey)
            animeCwMetaNegativeCacheTimestamps.remove(cacheKey)
        }
    }

    val idCandidates = buildList {
        add(progress.contentId)
        if (progress.contentId.startsWith("tmdb:")) add(progress.contentId.substringAfter(':'))
    }.distinct()

    val typeCandidates = listOf(progress.contentType, "series", "tv").distinct()
    val useAllAddons = false
    val resolved = run {
        var summary: CwMetaSummary? = null
        for (type in typeCandidates) {
            for (candidateId in idCandidates) {
                val result = withTimeoutOrNull(6_000L) {
                    if (useAllAddons) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    } else {
                        metaRepository.getMetaFromPrimaryAddon(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    }
                }
                summary = ((result as? NetworkResult.Success<*>)?.data as? Meta)?.toAnimeCwSummary()
                if (summary != null) break
            }
            if (summary != null) break
        }
        // Fallback: if primary addon failed, try all addons before giving up.
        if (summary == null && !useAllAddons) {
            for (type in typeCandidates) {
                for (candidateId in idCandidates) {
                    val fallbackResult = withTimeoutOrNull(6_000L) {
                        metaRepository.getMetaFromAllAddons(
                            type = type,
                            id = candidateId
                        ).first { it !is NetworkResult.Loading }
                    }
                    summary = ((fallbackResult as? NetworkResult.Success<*>)?.data as? Meta)?.toAnimeCwSummary()
                    if (summary != null) break
                }
                if (summary != null) break
            }
        }
        summary
    }

    synchronized(animeCwMetaCache) {
        animeCwMetaCache[cacheKey] = resolved
        if (resolved == null) {
            animeCwMetaNegativeCacheTimestamps[cacheKey] = System.currentTimeMillis()
        } else {
            animeCwMetaNegativeCacheTimestamps.remove(cacheKey)
        }
    }
    return resolved
}

private suspend fun AnimeHomeViewModel.findAnimeNextUpEpisodeFromMetaSeed(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): NextUpResolution? {
    val cacheKey = buildAnimeNextUpSeedCacheKey(progress, showUnairedNextUp)
    synchronized(animeCwNextUpResolutionCache) {
        if (animeCwNextUpResolutionCache.containsKey(cacheKey)) {
            val cached = animeCwNextUpResolutionCache[cacheKey]
            if (cached != null) {
                // Recompute hasAired from the release date so a resolution cached
                // while the episode was still unaired does not stay stuck as
                // "unaired" after the episode drops (same-session / same-day).
                val freshHasAired = hasAnimeEpisodeAired(cached.released, fallback = cached.hasAired)
                if (freshHasAired == cached.hasAired) return cached
                val refreshed = cached.copy(
                    hasAired = freshHasAired,
                    airDateLabel = if (freshHasAired) {
                        null
                    } else {
                        cached.airDateLabel
                            ?: cached.released?.let(::parseEpisodeReleaseDate)?.let(::formatAnimeEpisodeAirDateLabel)
                    }
                )
                animeCwNextUpResolutionCache[cacheKey] = refreshed
                return refreshed
            }
            // Negative cache entry — check TTL
            val negativeCachedAt = animeCwNextUpNegativeCacheTimestamps[cacheKey]
            if (negativeCachedAt != null &&
                System.currentTimeMillis() - negativeCachedAt < ANIME_CW_META_NEGATIVE_CACHE_TTL_MS
            ) {
                return null
            }
            // TTL expired — retry
            animeCwNextUpResolutionCache.remove(cacheKey)
            animeCwNextUpNegativeCacheTimestamps.remove(cacheKey)
        }
    }
    val contentId = progress.contentId
    val season = progress.season
    val episode = progress.episode
    if (season == null || episode == null || season == 0) {
        synchronized(animeCwNextUpResolutionCache) {
            animeCwNextUpResolutionCache[cacheKey] = null
            animeCwNextUpNegativeCacheTimestamps[cacheKey] = System.currentTimeMillis()
        }
        return null
    }

    val meta = resolveAnimeMetaForProgress(progress) ?: run {
        synchronized(animeCwNextUpResolutionCache) {
            animeCwNextUpResolutionCache[cacheKey] = null
            animeCwNextUpNegativeCacheTimestamps[cacheKey] = System.currentTimeMillis()
        }
        return null
    }
    val nextVideo = resolveAnimeNextUpVideoFromMeta(progress, meta, showUnairedNextUp)
    if (nextVideo == null) {
        synchronized(animeCwNextUpResolutionCache) {
            animeCwNextUpResolutionCache[cacheKey] = null
            animeCwNextUpNegativeCacheTimestamps[cacheKey] = System.currentTimeMillis()
        }
        return null
    }

    val nextSeason = nextVideo.season ?: return null
    val nextEpisode = nextVideo.episode ?: return null
    val rawReleased = nextVideo.released?.trim()?.takeIf { it.isNotBlank() }
    val computedHasAired = hasAnimeEpisodeAired(rawReleased, fallback = true)
    val resolution = NextUpResolution(
        season = nextSeason,
        episode = nextEpisode,
        videoId = nextVideo.id.takeIf { it.isNotBlank() }
            ?: buildAnimeLightweightEpisodeVideoId(
                contentId,
                nextSeason,
                nextEpisode
            ),
        episodeTitle = nextVideo.title?.takeIf { it.isNotBlank() },
        released = rawReleased,
        hasAired = computedHasAired,
        airDateLabel = rawReleased?.let(::parseEpisodeReleaseDate)?.let { releaseDate ->
            if (computedHasAired) null
            else formatAnimeEpisodeAirDateLabel(releaseDate)
        },
        lastWatched = progress.lastWatched
    )
    synchronized(animeCwNextUpResolutionCache) {
        animeCwNextUpResolutionCache[cacheKey] = resolution
    }
    return resolution
}

private suspend fun AnimeHomeViewModel.buildAnimeNextUpItem(
    progress: WatchProgress,
    showUnairedNextUp: Boolean
): ContinueWatchingItem.NextUp? {
    val nextUp = findAnimeNextUpEpisodeFromMetaSeed(
        progress = progress,
        showUnairedNextUp = showUnairedNextUp
    ) ?: return null
    val seedMeta = resolveAnimeMetaForProgress(progress)

    val name = progress.name.trim().takeIf { it.isNotEmpty() }
        ?: seedMeta?.name
        ?: progress.contentId
    val releaseState = resolveAnimeNextUpReleaseState(
        seedProgress = progress,
        nextSeason = nextUp.season,
        nextReleased = nextUp.released,
        hasAired = nextUp.hasAired
    )
    val nextUpVideo = seedMeta?.videos?.firstOrNull {
        it.season == nextUp.season && it.episode == nextUp.episode
    }
    val info = NextUpInfo(
        contentId = progress.contentId,
        contentType = progress.contentType,
        name = name,
        poster = progress.poster.normalizeImageUrl() ?: seedMeta?.poster.normalizeImageUrl(),
        backdrop = progress.backdrop.normalizeImageUrl() ?: seedMeta?.backdropUrl.normalizeImageUrl(),
        logo = progress.logo.normalizeImageUrl() ?: seedMeta?.logo.normalizeImageUrl(),
        videoId = nextUp.videoId,
        season = nextUp.season,
        episode = nextUp.episode,
        episodeTitle = nextUp.episodeTitle ?: nextUpVideo?.title,
        episodeDescription = nextUpVideo?.overview,
        thumbnail = nextUpVideo?.thumbnail.normalizeImageUrl(),
        released = nextUp.released,
        hasAired = nextUp.hasAired,
        airDateLabel = nextUp.airDateLabel,
        lastWatched = nextUp.lastWatched,
        imdbRating = null,
        genres = emptyList(),
        releaseInfo = null,
        sortTimestamp = releaseState.sortTimestamp,
        releaseTimestamp = releaseState.releaseTimestamp,
        isReleaseAlert = releaseState.isReleaseAlert,
        isNewSeasonRelease = releaseState.isNewSeasonRelease,
        seedSeason = progress.season,
        seedEpisode = progress.episode
    )
    return ContinueWatchingItem.NextUp(info)
}

private fun String?.normalizeImageUrl(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private suspend fun AnimeHomeViewModel.buildAnimeNextUpItems(
    allProgress: List<WatchProgress>,
    animeSeeds: List<WatchProgress>,
    inProgressItems: List<ContinueWatchingItem.InProgress>,
    dismissedNextUp: Set<String>,
    showUnairedNextUp: Boolean,
    nextUpFromFurthestEpisode: Boolean = true,
    onPartialUpdate: suspend (List<ContinueWatchingItem.NextUp>) -> Unit = {}
): List<ContinueWatchingItem.NextUp> = coroutineScope {
    // Move seed filtering/grouping/sorting off the main thread.
    val (latestCompletedBySeries, inProgressIds) = withContext(Dispatchers.Default) {
        val latestCompletedByContent = allProgress
            .asSequence()
            .filter { isSeriesTypeCW(it.contentType) }
            .filter { it.contentId.isNotBlank() }
            .filter { shouldUseAsCompletedSeed(it) }
            .groupBy { it.contentId }
            .mapValues { (_, items) ->
                items.maxOfOrNull { it.lastWatched } ?: Long.MIN_VALUE
            }

        val ipIds = inProgressItems
            .map { it.progress }
            .filter { progress ->
                shouldTreatAsActiveInProgressForNextUpSuppression(
                    progress = progress,
                    latestCompletedAt = latestCompletedByContent[progress.contentId]
                )
            }
            .map { it.contentId }
            .toSet()

        val seeds = animeSeeds
            .filter { progress ->
                isSeriesTypeCW(progress.contentType) &&
                    progress.season != null &&
                    progress.episode != null &&
                    progress.season != 0 &&
                    shouldUseAsCompletedSeed(progress)
            }
            .groupBy { it.contentId }
            .mapNotNull { (_, items) ->
                choosePreferredNextUpSeed(items, nextUpFromFurthestEpisode)
            }
            .filter { it.contentId !in ipIds }
            .filter { progress ->
                nextUpDismissKey(progress.contentId, progress.season, progress.episode) !in dismissedNextUp
            }
            .sortedByDescending { it.lastWatched }
            // Skip seeds validated as "no next-up" ONLY if the seed hasn't changed.
            // The cache key includes season+episode, so a changed seed (user watched
            // a new episode) produces a cache miss and is always processed.
            .filter { progress ->
                val cacheKey = buildAnimeNextUpSeedCacheKey(progress, showUnairedNextUp)
                val inCache = synchronized(animeCwNextUpResolutionCache) {
                    animeCwNextUpResolutionCache.containsKey(cacheKey)
                }
                if (!inCache) return@filter true // cache miss — seed changed or first time
                val cachedValue = synchronized(animeCwNextUpResolutionCache) {
                    animeCwNextUpResolutionCache[cacheKey]
                }
                if (cachedValue != null) return@filter true // positive hit — has next-up
                // Negative hit (no next-up) — skip if TTL is fresh
                val negativeCachedAt = synchronized(animeCwNextUpNegativeCacheTimestamps) {
                    animeCwNextUpNegativeCacheTimestamps[cacheKey]
                }
                if (negativeCachedAt == null) return@filter true
                System.currentTimeMillis() - negativeCachedAt >= ANIME_CW_META_NEGATIVE_CACHE_TTL_MS
            }
            .take(ANIME_CW_MAX_NEXT_UP_LOOKUPS)

        seeds to ipIds
    }

    if (latestCompletedBySeries.isEmpty()) {
        return@coroutineScope emptyList()
    }

    val lookupSemaphore = Semaphore(ANIME_CW_MAX_NEXT_UP_CONCURRENCY)
    val mergeMutex = Mutex()
    val nextUpByContent = linkedMapOf<String, ContinueWatchingItem.NextUp>()
    val processedContentIds = Collections.synchronizedSet(mutableSetOf<String>())
    val resolvedSinceLastPublish = AtomicInteger(0)
    // Batch partial updates: publish every N resolved items instead of after each one.
    val partialPublishBatchSize = (latestCompletedBySeries.size / 3).coerceIn(2, 8)

    val jobs = latestCompletedBySeries.map { progress ->
        launch(Dispatchers.IO) {
            lookupSemaphore.withPermit {
                processedContentIds.add(progress.contentId)
                // Remap seed from Trakt numbering to addon numbering (for anime).
                // Done here (after filters) so only ~5-10 seeds are remapped, not all.
                val remappedProgress = watchProgressRepository.prepareNextUpSeed(progress)
                val nextUp = buildAnimeNextUpItem(
                    progress = remappedProgress,
                    showUnairedNextUp = showUnairedNextUp
                ) ?: run {
                    // If meta was not available (network error, addon timeout),
                    // remove from processedContentIds so this series is NOT
                    // treated as "rejected". The cached CW snapshot will keep
                    // it visible until the next successful meta resolution.
                    val metaResolved = synchronized(animeCwMetaCache) {
                        animeCwMetaCache["${progress.contentType}:${progress.contentId}"]
                            ?: animeCwMetaCache["series:${progress.contentId}"]
                            ?: animeCwMetaCache["tv:${progress.contentId}"]
                    } != null
                    if (!metaResolved) {
                        processedContentIds.remove(progress.contentId)
                    }
                    return@withPermit
                }
                val shouldPublish: Boolean
                val partialItems = mergeMutex.withLock {
                    nextUpByContent[progress.contentId] = nextUp
                    val count = resolvedSinceLastPublish.incrementAndGet()
                    shouldPublish = count >= partialPublishBatchSize
                    if (shouldPublish) resolvedSinceLastPublish.set(0)
                    if (shouldPublish) nextUpByContent.values.toList() else emptyList()
                }
                if (shouldPublish) {
                    onPartialUpdate(partialItems)
                }
            }
        }
    }
    jobs.joinAll()

    // Store which contentIds were evaluated so olderToInclude can skip series
    // that were already processed (and possibly rejected).
    synchronized(animeCwLastProcessedNextUpContentIds) {
        animeCwLastProcessedNextUpContentIds.clear()
        animeCwLastProcessedNextUpContentIds.addAll(processedContentIds)
    }

    nextUpByContent.values.toList()
}

private fun AnimeHomeViewModel.shouldUseAsCompletedSeed(progress: WatchProgress): Boolean {
    if (isMalformedNextUpSeedContentId(progress.contentId)) return false
    return watchProgressRepository.shouldUseAsNextUpSeed(progress, System.currentTimeMillis())
}

private fun AnimeHomeViewModel.shouldTreatAsActiveInProgressForNextUpSuppression(
    progress: WatchProgress,
    latestCompletedAt: Long?
): Boolean {
    if (!shouldTreatAsInProgressForContinueWatching(progress)) return false
    if (latestCompletedAt == null || latestCompletedAt == Long.MIN_VALUE) return true
    return progress.lastWatched >= latestCompletedAt
}

private suspend fun AnimeHomeViewModel.applyConclusiveAnimeOlderNextUpResults(
    resolvedItems: List<ContinueWatchingItem.NextUp>,
    conclusivelyProcessedContentIds: Set<String>,
    dismissedNextUpKeys: Set<String>,
    sortMode: ContinueWatchingSortMode,
    persistSnapshot: Boolean
) {    if (resolvedItems.isEmpty() && conclusivelyProcessedContentIds.isEmpty()) return

    val acceptedResolvedItems = resolvedItems
        .distinctBy { item -> item.info.contentId }
        .filter { item ->
            item.info.contentId in conclusivelyProcessedContentIds &&
                nextUpDismissKey(
                    item.info.contentId,
                    item.info.seedSeason,
                    item.info.seedEpisode
                ) !in dismissedNextUpKeys
        }
    val reconciledContentIds = conclusivelyProcessedContentIds

    synchronized(animeDiscoveredOlderNextUpItems) {
        animeDiscoveredOlderNextUpItems.removeAll { item ->
            item.info.contentId in reconciledContentIds
        }
        animeDiscoveredOlderNextUpItems.addAll(acceptedResolvedItems)
    }
    reconciledContentIds.forEach(animeCwEnrichedNextUpOverlay::remove)

    _uiState.update { state ->
        val reconciled = reconcileConclusiveNextUpItems(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            resolvedItems = acceptedResolvedItems,
            conclusivelyProcessedContentIds = conclusivelyProcessedContentIds,
            dismissedNextUpKeys = dismissedNextUpKeys
        )
        val sorted = sortContinueWatchingItems(reconciled, sortMode)
        val (main, upcoming) = splitUpcomingItems(sorted, sortMode)
        if (state.continueWatchingItems == main && state.upcomingItems == upcoming) {
            state
        } else {
            state.copy(continueWatchingItems = main, upcomingItems = upcoming)
        }
    }

    if (!persistSnapshot) return
    withContext(Dispatchers.IO) {
        val snapshot = (_uiState.value.continueWatchingItems + _uiState.value.upcomingItems)
            .toCachedNextUpSnapshot(brokenImageUrls)
        runCatching { animeCwEnrichmentCache.saveNextUpSnapshot(snapshot, force = true) }
    }
}

// --- Enrichment dei visibili (senza TMDB) ---

private const val ANIME_CW_MAX_ENRICHMENT_CONCURRENCY = 4

internal fun AnimeHomeViewModel.removeAnimeContinueWatchingPipeline(
    contentId: String,
    season: Int? = null,
    episode: Int? = null,
    isNextUp: Boolean = false
) {
    if (isNextUp) {
        val dismissKey = nextUpDismissKey(contentId, season, episode)
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(
                                item.info.contentId,
                                item.info.seedSeason,
                                item.info.seedEpisode
                            ) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                },
                upcomingItems = state.upcomingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.NextUp ->
                            nextUpDismissKey(
                                item.info.contentId,
                                item.info.seedSeason,
                                item.info.seedEpisode
                            ) == dismissKey
                        is ContinueWatchingItem.InProgress -> false
                    }
                }
            )
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.addDismissedNextUpKey(dismissKey)
        }
        return
    }
    viewModelScope.launch {
        // Optimistic UI: remove the item from the CW list immediately
        // so the user sees instant feedback while the DataStore write propagates.
        _uiState.update { state ->
            state.copy(
                continueWatchingItems = state.continueWatchingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.InProgress -> item.progress.contentId == contentId
                        is ContinueWatchingItem.NextUp -> item.info.contentId == contentId
                    }
                },
                upcomingItems = state.upcomingItems.filterNot { item ->
                    when (item) {
                        is ContinueWatchingItem.InProgress -> item.progress.contentId == contentId
                        is ContinueWatchingItem.NextUp -> item.info.contentId == contentId
                    }
                }
            )
        }
        val targetSeason = if (isNextUp) season else null
        val targetEpisode = if (isNextUp) episode else null
        watchProgressRepository.removeProgress(
            contentId = contentId,
            season = targetSeason,
            episode = targetEpisode
        )
    }
}

private fun AnimeHomeViewModel.persistAnimeCwSnapshotsFromUi() {
    viewModelScope.launch(Dispatchers.IO) {
        val currentItems = _uiState.value.continueWatchingItems + _uiState.value.upcomingItems
        val nextUpSnap = currentItems.toCachedNextUpSnapshot(brokenImageUrls)
        val ipSnap = currentItems.mapNotNull { item ->
            val ip = item as? ContinueWatchingItem.InProgress ?: return@mapNotNull null
            val p = ip.progress
            CachedInProgressItem(
                contentId = p.contentId, contentType = p.contentType, name = p.name,
                poster = p.poster, backdrop = p.backdrop, logo = p.logo,
                videoId = p.videoId, season = p.season, episode = p.episode,
                episodeTitle = p.episodeTitle, position = p.position, duration = p.duration,
                lastWatched = p.lastWatched, progressPercent = p.progressPercent,
                episodeThumbnail = ip.episodeThumbnail?.takeIf { it !in brokenImageUrls },
                episodeDescription = ip.episodeDescription, episodeImdbRating = ip.episodeImdbRating,
                genres = ip.genres, releaseInfo = ip.releaseInfo,
                contentLanguage = ip.contentLanguage
            )
        }
        runCatching { animeCwEnrichmentCache.saveNextUpSnapshot(nextUpSnap, force = true) }
        runCatching { animeCwEnrichmentCache.saveInProgressSnapshot(ipSnap, force = true) }
    }
}

private fun NextUpInfo.toAnimeProgressSeed(): WatchProgress {
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = videoId,
        season = seedSeason ?: season,
        episode = seedEpisode ?: episode,
        episodeTitle = episodeTitle,
        position = 1L,
        duration = 1L,
        lastWatched = lastWatched
    )
}

private fun resolveAnimeVideoForProgress(progress: WatchProgress, meta: CwMetaSummary): CwVideoSummary? {
    if (!isSeriesTypeCW(progress.contentType)) return null
    val videos = meta.videos.filter { it.season != null && it.episode != null && it.season != 0 }
    if (videos.isEmpty()) return null

    progress.videoId.takeIf { it.isNotBlank() }?.let { videoId ->
        videos.firstOrNull { it.id == videoId }?.let { return it }
    }

    val season = progress.season
    val episode = progress.episode
    if (season != null && episode != null) {
        videos.firstOrNull { it.season == season && it.episode == episode }?.let { return it }

        // Fallback: if not found by season+episode (anime with absolute numbering
        // on Trakt vs multi-season on addon), try global index matching.
        val addonSeasons = videos.mapTo(mutableSetOf()) { it.season }
        if (season == 1 && addonSeasons.size > 1 && episode > 0) {
            val sorted = videos.sortedWith(
                compareBy<CwVideoSummary>({ it.season ?: Int.MAX_VALUE }, { it.episode ?: Int.MAX_VALUE })
            )
            val globalIndex = episode - 1
            if (globalIndex in sorted.indices) {
                return sorted[globalIndex]
            }
        }
    }

    return null
}

private suspend fun AnimeHomeViewModel.enrichAnimeInProgressItem(
    item: ContinueWatchingItem.InProgress
): ContinueWatchingItem.InProgress {
    val meta = resolveAnimeMetaForProgress(item.progress)
        ?.let { applyAnimeExternalEnrichmentToCwMeta(it, item.progress.contentType) }
        ?: return item
    val video = resolveAnimeVideoForProgress(item.progress, meta)
    return item.copy(
        progress = item.progress.copy(
            name = meta.name,
            poster = item.progress.poster ?: meta.poster.normalizeImageUrl(),
            backdrop = meta.backdropUrl.normalizeImageUrl() ?: item.progress.backdrop,
            logo = meta.logo.normalizeImageUrl() ?: item.progress.logo,
            episodeTitle = video?.title?.takeIf { it.isNotBlank() } ?: item.progress.episodeTitle
        ),
        episodeDescription = video?.overview?.takeIf { it.isNotBlank() }
            ?: meta.description?.takeIf { it.isNotBlank() }
            ?: item.episodeDescription,
        episodeThumbnail = video?.thumbnail.normalizeImageUrl() ?: item.episodeThumbnail,
        genres = meta.genres.take(3),
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.releaseInfo
    )
}

private suspend fun AnimeHomeViewModel.enrichAnimeNextUpItem(
    item: ContinueWatchingItem.NextUp
): ContinueWatchingItem.NextUp {
    val progressSeed = item.info.toAnimeProgressSeed()
    val meta = resolveAnimeMetaForProgress(progressSeed)
        ?.let { applyAnimeExternalEnrichmentToCwMeta(it, progressSeed.contentType) }
        ?: return item
    val video = resolveAnimeNextUpVideoFromMeta(progressSeed, meta, showUnairedNextUp = true)

    val released = video?.released ?: item.info.released
    val releaseDate = parseEpisodeReleaseDate(released)
    val hasAired = hasAnimeEpisodeAired(released, fallback = item.info.hasAired)
    val releaseState = resolveAnimeNextUpReleaseState(
        seedProgress = progressSeed,
        nextSeason = video?.season ?: item.info.season,
        nextReleased = released,
        hasAired = hasAired
    )

    val enrichedInfo = item.info.copy(
        name = meta.name,
        poster = item.info.poster ?: meta.poster.normalizeImageUrl(),
        backdrop = meta.backdropUrl.normalizeImageUrl() ?: item.info.backdrop,
        logo = meta.logo.normalizeImageUrl() ?: item.info.logo,
        season = video?.season ?: item.info.season,
        episode = video?.episode ?: item.info.episode,
        videoId = video?.id?.takeIf { it.isNotBlank() } ?: item.info.videoId,
        episodeTitle = video?.title?.takeIf { it.isNotBlank() } ?: item.info.episodeTitle,
        episodeDescription = video?.overview?.takeIf { it.isNotBlank() } ?: item.info.episodeDescription,
        thumbnail = video?.thumbnail.normalizeImageUrl() ?: item.info.thumbnail,
        released = released,
        hasAired = hasAired,
        airDateLabel = if (hasAired || releaseDate == null) null else formatAnimeEpisodeAirDateLabel(releaseDate),
        imdbRating = meta.imdbRating ?: item.info.imdbRating,
        genres = meta.genres.take(3).ifEmpty { item.info.genres },
        releaseInfo = meta.releaseInfo?.takeIf { it.isNotBlank() } ?: item.info.releaseInfo,
        sortTimestamp = releaseState.sortTimestamp,
        releaseTimestamp = releaseState.releaseTimestamp,
        isReleaseAlert = releaseState.isReleaseAlert,
        isNewSeasonRelease = releaseState.isNewSeasonRelease,
        contentLanguage = item.info.contentLanguage
    )
    return item.copy(info = enrichedInfo)
}

private suspend fun AnimeHomeViewModel.enrichAnimeVisibleContinueWatchingItems(
    finalItems: List<ContinueWatchingItem>,
    sortMode: ContinueWatchingSortMode
): Boolean = coroutineScope {
    if (finalItems.isEmpty()) return@coroutineScope false

    val enrichmentSemaphore = Semaphore(ANIME_CW_MAX_ENRICHMENT_CONCURRENCY)
    val enrichedItems = finalItems
        .mapIndexed { index, item ->
            async(Dispatchers.IO) {
                enrichmentSemaphore.withPermit {
                    index to when (item) {
                        is ContinueWatchingItem.InProgress -> enrichAnimeInProgressItem(item)
                        is ContinueWatchingItem.NextUp -> enrichAnimeNextUpItem(item)
                    }
                }
            }
        }
        .awaitAll()
        .sortedBy { it.first }
        .map { it.second }

    // Re-sort if any sortTimestamp changed during enrichment (e.g. release alert
    // detected after a more accurate release date came from the addon).
    val sortChanged = enrichedItems.zip(finalItems).any { (enriched, original) ->
        val enrichedTs = when (enriched) {
            is ContinueWatchingItem.InProgress -> enriched.progress.lastWatched
            is ContinueWatchingItem.NextUp -> enriched.info.sortTimestamp
        }
        val originalTs = when (original) {
            is ContinueWatchingItem.InProgress -> original.progress.lastWatched
            is ContinueWatchingItem.NextUp -> original.info.sortTimestamp
        }
        enrichedTs != originalTs
    }
    val sortedEnrichedItems = if (sortChanged) {
        sortContinueWatchingItems(enrichedItems, sortMode)
    } else {
        enrichedItems
    }

    if (sortedEnrichedItems == finalItems) return@coroutineScope false

    // Save enriched next-up info to in-memory overlay so the next CW cycle's
    // cached/partial/normal emissions use enriched data from the start,
    // preventing title/thumbnail flickering between addon values.
    sortedEnrichedItems.forEach { item ->
        when (item) {
            is ContinueWatchingItem.NextUp -> {
                animeCwEnrichedNextUpOverlay[item.info.contentId] = item.info
            }
            is ContinueWatchingItem.InProgress -> {
                animeCwEnrichedInProgressOverlay[item.progress.contentId] = item
            }
        }
    }

    _uiState.update { state ->
        val mergedEnrichedItems = mergeConcurrentContinueWatchingEnrichment(
            currentItems = state.continueWatchingItems + state.upcomingItems,
            originalItems = finalItems,
            enrichedItems = enrichedItems
        )
        val sortedMergedItems = sortContinueWatchingItems(
            mergedEnrichedItems,
            sortMode
        )
        val (enrichedMain, enrichedUpcoming) = splitUpcomingItems(
            sortedMergedItems,
            sortMode
        )
        if (state.continueWatchingItems == enrichedMain && state.upcomingItems == enrichedUpcoming) {
            state
        } else {
            state.copy(continueWatchingItems = enrichedMain, upcomingItems = enrichedUpcoming)
        }
    }
    persistAnimeCwSnapshotsFromUi()
    true
}
