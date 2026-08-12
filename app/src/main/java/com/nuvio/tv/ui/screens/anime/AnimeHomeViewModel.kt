package com.nuvio.tv.ui.screens.anime

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.NextUpResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AnimeHomeViewModel @Inject constructor(
    internal val animeAddonRepository: AnimeAddonRepository,
    private val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    @Named("anime_layout") internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("anime_cw_cache") internal val animeCwEnrichmentCache: ContinueWatchingEnrichmentCache
) : ViewModel() {

    companion object {
        private const val TAG = "AnimeHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    internal val _uiState = MutableStateFlow(AnimeHomeUiState())

    internal val animeCwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val animeCwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val animeCwNextUpResolutionCache =
        Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val animeCwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val animeDiscoveredOlderNextUpItems =
        Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val animeCwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val animeCwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    internal val animeCwEnrichedInProgressOverlay =
        ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    internal var animeCwPipelineJob: Job? = null
    val uiState: StateFlow<AnimeHomeUiState> = _uiState.asStateFlow()

    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val rows = LinkedHashMap<String, CatalogRow>()
    private val catalogLoadMutex = Mutex()
    private var pendingLoads = 0
    private var lastAddons: List<Addon> = emptyList()

    private val layoutOrderKeys = mutableListOf<String>()
    private val layoutDisabledKeys = mutableSetOf<String>()
    private val heroCatalogKeys = mutableListOf<String>()
    private var heroSectionEnabled = true
    private var homeLayout = HomeLayout.MODERN
    private var catalogTypeSuffixEnabled = true
    private var hideUnreleasedContent = false
    private var followAddonsOrder = false
    private var modernLandscapePostersEnabled = false
    private var modernHeroFullScreenBackdropEnabled = false
    private var classicFocusGradientEnabled = false
    private var continueWatchingCardStyle = ContinueWatchingCardStyle.CARD
    private var useEpisodeThumbnailsInCw = true
    private var blurContinueWatchingNextUp = false
    private var posterCardWidthDp = 126
    private var posterCardHeightDp = 189
    private var posterCardCornerRadiusDp = 12
    private var posterLabelsEnabled = true
    private var catalogAddonNameEnabled = true
    private var focusedPosterBackdropExpandEnabled = false
    private var focusedPosterBackdropExpandDelaySeconds = 3
    private var focusedPosterBackdropTrailerEnabled = false
    private var focusedPosterBackdropTrailerMuted = true

    init {
        observeLayoutPreferences()
        observeAnimeAddons()
        observeAnimeContinueWatching()
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            val coreSnapshotFlow = combine(
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.heroCatalogSelections,
                layoutPreferenceDataStore.heroSectionEnabled,
                layoutPreferenceDataStore.selectedLayout
            ) { orderKeys, disabledKeys, heroKeys, heroEnabled, layout ->
                LayoutSnapshot(
                    orderKeys = orderKeys,
                    disabledKeys = disabledKeys.toSet(),
                    heroKeys = heroKeys,
                    heroEnabled = heroEnabled,
                    layout = layout
                )
            }
            val baseSnapshotFlow = combine(
                coreSnapshotFlow,
                layoutPreferenceDataStore.catalogTypeSuffixEnabled,
                layoutPreferenceDataStore.hideUnreleasedContent,
                layoutPreferenceDataStore.followAddonsOrder
            ) { snapshot, typeSuffix, hideUnreleased, followAddons ->
                snapshot.copy(
                    catalogTypeSuffixEnabled = typeSuffix,
                    hideUnreleasedContent = hideUnreleased,
                    followAddonsOrder = followAddons
                )
            }
            val viewSnapshotFlow = combine(
                baseSnapshotFlow,
                layoutPreferenceDataStore.modernLandscapePostersEnabled,
                layoutPreferenceDataStore.modernHeroFullScreenBackdropEnabled,
                layoutPreferenceDataStore.classicFocusGradientEnabled,
                layoutPreferenceDataStore.continueWatchingCardStyle
            ) { snapshot, landscape, fullscreenBackdrop, focusGradient, cardStyle ->
                snapshot.copy(
                    modernLandscapePostersEnabled = landscape,
                    modernHeroFullScreenBackdropEnabled = fullscreenBackdrop,
                    classicFocusGradientEnabled = focusGradient,
                    continueWatchingCardStyle = cardStyle
                )
            }
            val focusedPosterSnapshotFlow = combine(
                viewSnapshotFlow,
                layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
                layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerEnabled
            ) { snapshot, backdropExpand, backdropExpandDelay, trailerEnabled ->
                snapshot.copy(
                    focusedPosterBackdropExpandEnabled = backdropExpand,
                    focusedPosterBackdropExpandDelaySeconds = backdropExpandDelay,
                    focusedPosterBackdropTrailerEnabled = trailerEnabled
                )
            }
            val focusedPosterMutedFlow = combine(
                focusedPosterSnapshotFlow,
                layoutPreferenceDataStore.focusedPosterBackdropTrailerMuted
            ) { snapshot, trailerMuted ->
                snapshot.copy(focusedPosterBackdropTrailerMuted = trailerMuted)
            }
            val cardStyleSnapshotFlow = combine(
                focusedPosterMutedFlow,
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp,
                layoutPreferenceDataStore.posterLabelsEnabled
            ) { snapshot, width, height, cornerRadius, posterLabels ->
                snapshot.copy(
                    posterCardWidthDp = width,
                    posterCardHeightDp = height,
                    posterCardCornerRadiusDp = cornerRadius,
                    posterLabelsEnabled = posterLabels
                )
            }
            val cardStyleWithAddonNameFlow = combine(
                cardStyleSnapshotFlow,
                layoutPreferenceDataStore.catalogAddonNameEnabled
            ) { snapshot, addonName ->
                snapshot.copy(catalogAddonNameEnabled = addonName)
            }
            combine(
                cardStyleWithAddonNameFlow,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw,
                layoutPreferenceDataStore.blurContinueWatchingNextUp
            ) { snapshot, thumbnails, blurNextUp ->
                snapshot.copy(
                    useEpisodeThumbnailsInCw = thumbnails,
                    blurContinueWatchingNextUp = blurNextUp
                )
            }.distinctUntilChanged().collectLatest { snapshot ->
                layoutOrderKeys.clear()
                layoutOrderKeys.addAll(snapshot.orderKeys)
                layoutDisabledKeys.clear()
                layoutDisabledKeys.addAll(snapshot.disabledKeys)
                heroCatalogKeys.clear()
                heroCatalogKeys.addAll(snapshot.heroKeys)
                heroSectionEnabled = snapshot.heroEnabled
                homeLayout = snapshot.layout
                catalogTypeSuffixEnabled = snapshot.catalogTypeSuffixEnabled
                hideUnreleasedContent = snapshot.hideUnreleasedContent
                followAddonsOrder = snapshot.followAddonsOrder
                modernLandscapePostersEnabled = snapshot.modernLandscapePostersEnabled
                modernHeroFullScreenBackdropEnabled = snapshot.modernHeroFullScreenBackdropEnabled
                classicFocusGradientEnabled = snapshot.classicFocusGradientEnabled
                continueWatchingCardStyle = snapshot.continueWatchingCardStyle
                useEpisodeThumbnailsInCw = snapshot.useEpisodeThumbnailsInCw
                blurContinueWatchingNextUp = snapshot.blurContinueWatchingNextUp
                posterCardWidthDp = snapshot.posterCardWidthDp
                posterCardHeightDp = snapshot.posterCardHeightDp
                posterCardCornerRadiusDp = snapshot.posterCardCornerRadiusDp
                posterLabelsEnabled = snapshot.posterLabelsEnabled
                catalogAddonNameEnabled = snapshot.catalogAddonNameEnabled
                focusedPosterBackdropExpandEnabled = snapshot.focusedPosterBackdropExpandEnabled
                focusedPosterBackdropExpandDelaySeconds = snapshot.focusedPosterBackdropExpandDelaySeconds
                focusedPosterBackdropTrailerEnabled = snapshot.focusedPosterBackdropTrailerEnabled
                focusedPosterBackdropTrailerMuted = snapshot.focusedPosterBackdropTrailerMuted
                publishRows()
            }
        }
    }

    private data class LayoutSnapshot(
        val orderKeys: List<String>,
        val disabledKeys: Set<String>,
        val heroKeys: List<String>,
        val heroEnabled: Boolean,
        val layout: HomeLayout,
        val catalogTypeSuffixEnabled: Boolean = true,
        val hideUnreleasedContent: Boolean = false,
        val followAddonsOrder: Boolean = false,
        val modernLandscapePostersEnabled: Boolean = false,
        val modernHeroFullScreenBackdropEnabled: Boolean = false,
        val classicFocusGradientEnabled: Boolean = false,
        val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
        val useEpisodeThumbnailsInCw: Boolean = true,
        val blurContinueWatchingNextUp: Boolean = false,
        val posterCardWidthDp: Int = 126,
        val posterCardHeightDp: Int = 189,
        val posterCardCornerRadiusDp: Int = 12,
        val posterLabelsEnabled: Boolean = true,
        val catalogAddonNameEnabled: Boolean = true,
        val focusedPosterBackdropExpandEnabled: Boolean = false,
        val focusedPosterBackdropExpandDelaySeconds: Int = 3,
        val focusedPosterBackdropTrailerEnabled: Boolean = false,
        val focusedPosterBackdropTrailerMuted: Boolean = true
    )

    private fun observeAnimeAddons() {
        viewModelScope.launch {
            animeAddonRepository.getInstalledAnimeAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    lastAddons = addons
                    val enabled = addons.filter { it.enabled }
                    if (enabled.isEmpty()) {
                        synchronized(rows) { rows.clear() }
                        publishRows()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                installedAddonsCount = addons.size
                            )
                        }
                        return@collectLatest
                    }
                    loadAllCatalogs(enabled)
                }
        }
    }

    private fun catalogKey(addon: Addon, catalog: CatalogDescriptor): String =
        "${addon.id}_${catalog.apiType}_${catalog.id}"

    private fun shouldShowCatalog(catalog: CatalogDescriptor): Boolean {
        val isSearchOnly = catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
        if (isSearchOnly) return false
        return !catalog.hasExplicitShowInHome || catalog.showInHome
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>) {
        _uiState.update {
            it.copy(isLoading = true, error = null, installedAddonsCount = addons.size)
        }
        synchronized(rows) {
            rows.clear()
            addons.forEach { addon ->
                addon.catalogs
                    .filter(::shouldShowCatalog)
                    .forEach { catalog -> rows[catalogKey(addon, catalog)] = emptyRow(addon, catalog) }
            }
        }
        publishRows()

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs.filter(::shouldShowCatalog).map { addon to it }
        }
        pendingLoads = catalogsToLoad.size

        catalogsToLoad.forEach { (addon, catalog) ->
            viewModelScope.launch {
                catalogLoadMutex.withLock {
                    loadCatalog(addon, catalog)
                }
            }
        }
    }

    private fun emptyRow(addon: Addon, catalog: CatalogDescriptor): CatalogRow {
        val placeholderItems = (0 until 8).map { i ->
            MetaPreview(
                id = "__placeholder_${addon.id}_${catalog.apiType}_${catalog.id}_$i",
                type = ContentType.fromString(catalog.apiType),
                rawType = catalog.apiType,
                name = " ",
                poster = PLACEHOLDER_IMAGE_URL,
                posterShape = PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = " ",
                imdbRating = null,
                genres = emptyList()
            )
        }
        return CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.type,
            rawType = catalog.apiType,
            items = placeholderItems,
            isLoading = true,
            hasMore = false,
            supportsSkip = catalog.supportsExtra("skip"),
            skipStep = catalog.skipStep()
        )
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor) {
        val key = catalogKey(addon, catalog)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        Log.d(TAG, "Loading anime catalog addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id}")
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    synchronized(rows) { rows[key] = result.data }
                    pendingLoads = (pendingLoads - 1).coerceAtLeast(0)
                    publishRows()
                    if (pendingLoads == 0) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                is NetworkResult.Error -> {
                    synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false, items = emptyList()) }
                    pendingLoads = (pendingLoads - 1).coerceAtLeast(0)
                    publishRows()
                    if (pendingLoads == 0) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                NetworkResult.Loading -> { /* handled by row */ }
            }
        }
    }

    fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val currentRow = synchronized(rows) { rows[key] }
        if (currentRow == null || currentRow.isLoading || !currentRow.hasMore) return

        synchronized(rows) { rows[key] = currentRow.copy(isLoading = true) }
        publishRows()

        viewModelScope.launch {
            val nextSkip = currentRow.nextCatalogSkip()
            catalogRepository.getCatalog(
                addonBaseUrl = currentRow.addonBaseUrl,
                addonId = currentRow.addonId,
                addonName = currentRow.addonName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = type,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        synchronized(rows) {
                            val latest = rows[key]
                            if (latest != null) {
                                rows[key] = latest.mergeCatalogPage(result.data)
                            }
                        }
                        publishRows()
                    }
                    is NetworkResult.Error -> {
                        synchronized(rows) {
                            val latest = rows[key]
                            if (latest != null) {
                                rows[key] = latest.copy(isLoading = false)
                            }
                        }
                        publishRows()
                    }
                    NetworkResult.Loading -> { }
                }
            }
        }
    }

    /**
     * Ensures the given catalog row is present (used by the See All screen when
     * navigating before the home rows finished loading).
     */
    fun ensureCatalogLoaded(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val existing = synchronized(rows) { rows[key] }
        val hasRealContent = existing != null &&
            existing.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
        if (hasRealContent) return

        val addon = lastAddons.firstOrNull { it.id == addonId } ?: return
        val catalog = addon.catalogs.firstOrNull { it.apiType == type && it.id == catalogId } ?: return
        if (!shouldShowCatalog(catalog)) return

        viewModelScope.launch {
            catalogLoadMutex.withLock {
                val current = synchronized(rows) { rows[key] }
                val currentHasRealContent = current != null &&
                    current.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
                if (currentHasRealContent) return@withLock
                loadCatalog(addon, catalog)
            }
        }
    }

    private fun publishRows() {
        val snapshot = synchronized(rows) { rows.values.toList() }
        if (_fullCatalogRows.value != snapshot) {
            _fullCatalogRows.value = snapshot
        }
        val ordered = orderRows(snapshot)
        val today = java.time.LocalDate.now()
        val released = if (hideUnreleasedContent) {
            ordered.map { it.filterReleasedItems(today) }
        } else {
            ordered
        }
        val filtered = released.filter { it.items.isNotEmpty() }
        val heroRow = computeHeroRow(filtered)
        val heroItems = heroRow?.items.orEmpty()
        _uiState.update { state ->
            val updated = state.copy(
                rows = filtered,
                heroItem = heroRow?.items?.firstOrNull(),
                heroItems = heroItems,
                heroAddonBaseUrl = heroRow?.addonBaseUrl,
                homeLayout = homeLayout,
                catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
                hideUnreleasedContent = hideUnreleasedContent,
                modernLandscapePostersEnabled = modernLandscapePostersEnabled,
                modernHeroFullScreenBackdropEnabled = modernHeroFullScreenBackdropEnabled,
                classicFocusGradientEnabled = classicFocusGradientEnabled,
                continueWatchingCardStyle = continueWatchingCardStyle,
                useEpisodeThumbnailsInCw = useEpisodeThumbnailsInCw,
                blurContinueWatchingNextUp = blurContinueWatchingNextUp,
                posterCardWidthDp = posterCardWidthDp,
                posterCardHeightDp = posterCardHeightDp,
                posterCardCornerRadiusDp = posterCardCornerRadiusDp,
                posterLabelsEnabled = posterLabelsEnabled,
                catalogAddonNameEnabled = catalogAddonNameEnabled,
                focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted
            )
            if (updated == state) state else updated
        }
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        when (item) {
            is ContinueWatchingItem.InProgress -> removeAnimeContinueWatchingPipeline(
                contentId = item.progress.contentId,
                season = item.progress.season,
                episode = item.progress.episode,
                isNextUp = false
            )
            is ContinueWatchingItem.NextUp -> removeAnimeContinueWatchingPipeline(
                contentId = item.info.contentId,
                season = item.info.seedSeason,
                episode = item.info.seedEpisode,
                isNextUp = true
            )
        }
    }

    private fun orderRows(all: List<CatalogRow>): List<CatalogRow> {
        val enabled = all.filterNot {
            layoutDisabledKeys.contains(homeCatalogKey(it.addonId, it.rawType, it.catalogId))
        }
        // In follow addons order mode, catalogs always stay in manifest order.
        if (followAddonsOrder || layoutOrderKeys.isEmpty()) return enabled
        val ordered = mutableListOf<CatalogRow>()
        val remaining = enabled.toMutableList()
        for (key in layoutOrderKeys) {
            val match = remaining.firstOrNull {
                homeCatalogKey(it.addonId, it.rawType, it.catalogId) == key
            }
            if (match != null) {
                ordered.add(match)
                remaining.remove(match)
            }
        }
        ordered.addAll(remaining)
        return ordered
    }

    private fun computeHeroRow(rows: List<CatalogRow>): CatalogRow? {
        if (!heroSectionEnabled || rows.isEmpty()) return null
        fun isRealRow(row: CatalogRow): Boolean =
            row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
        val byKey = rows.associateBy { homeCatalogKey(it.addonId, it.rawType, it.catalogId) }
        for (key in heroCatalogKeys) {
            byKey[key]?.takeIf(::isRealRow)?.let { return it }
        }
        return rows.firstOrNull(::isRealRow)
    }

    fun onEvent(event: AnimeHomeEvent) {
        when (event) {
            is AnimeHomeEvent.OnLoadMoreCatalog ->
                loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            AnimeHomeEvent.OnRetry -> {
                viewModelScope.launch {
                    val addons = lastAddons.filter { it.enabled }
                    if (addons.isNotEmpty()) loadAllCatalogs(addons)
                }
            }
        }
    }
}
