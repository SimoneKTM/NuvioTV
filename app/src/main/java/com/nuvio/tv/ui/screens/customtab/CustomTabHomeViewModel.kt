package com.nuvio.tv.ui.screens.customtab

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.CustomTabSource
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
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
class CustomTabHomeViewModel @Inject constructor(
    internal val animeAddonRepository: AnimeAddonRepository,
    private val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    @Named("custom_tab_layout") internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("custom_tab_cw_cache") internal val customTabCwEnrichmentCache: ContinueWatchingEnrichmentCache,
    @Named("custom_tab_tmdb") internal val customTabTmdbSettingsDataStore: TmdbSettingsDataStore,
    @Named("custom_tab_mdblist") internal val customTabMdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val mdbListRepository: MDBListRepository
) : ViewModel() {

    companion object {
        private const val TAG = "CustomTabHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    private var currentTabId: String = ""

    internal val _uiState = MutableStateFlow(CustomTabHomeUiState(
        customTab = CustomTab(id = "", name = "", sources = emptyList())
    ))

    internal val customTabCwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val customTabCwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val customTabCwNextUpResolutionCache =
        Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val customTabCwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val customTabDiscoveredOlderNextUpItems =
        Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val customTabCwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val customTabCwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    internal val customTabCwEnrichedInProgressOverlay =
        ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    internal var customTabCwPipelineJob: Job? = null
    internal var currentCustomTabTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentCustomTabMdbListSettings: MDBListSettings = MDBListSettings()
    internal var customTabHeroEnrichmentJob: Job? = null
    internal var lastCustomTabHeroEnrichmentSignature: String? = null
    val uiState: StateFlow<CustomTabHomeUiState> = _uiState.asStateFlow()

    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val rows = java.util.concurrent.ConcurrentHashMap<String, CatalogRow>()
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
    private var continueWatchingCardStyle = com.nuvio.tv.domain.model.ContinueWatchingCardStyle.CARD
    private var useEpisodeThumbnailsInCw = true
    private var blurContinueWatchingNextUp = false
    private var posterCardWidthDp = 126
    private var posterCardHeightDp = 189
    private var posterCardCornerRadiusDp = 12
    private var posterLabelsEnabled = true
    private var catalogAddonNameEnabled = false
    private var focusedPosterBackdropExpandEnabled = false
    private var focusedPosterBackdropExpandDelaySeconds = 3
    private var focusedPosterBackdropTrailerEnabled = false
    private var focusedPosterBackdropTrailerMuted = true
    private var focusedPosterBackdropTrailerPlaybackTarget = com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA

    fun setTabId(tabId: String) {
        if (currentTabId != tabId) {
            currentTabId = tabId
            resetState()
            observeCustomTab()
        }
    }

    private fun resetState() {
        rows.clear()
        pendingLoads = 0
        _uiState.update { it.copy(
            customTab = CustomTab(id = currentTabId, name = "", sources = emptyList()),
            isLoading = true,
            installedAddonsCount = 0,
            rows = emptyList()
        ) }
    }

    private fun observeCustomTab() {
        viewModelScope.launch {
            layoutPreferenceDataStore.customTabs
                .distinctUntilChanged()
                .collect { tabs ->
                    val tab = tabs.find { it.id == currentTabId }
                    if (tab != null) {
                        _uiState.update { it.copy(customTab = tab) }
                        observeLayoutPreferences()
                        observeCustomTabAddons()
                        observeCustomTabContinueWatching()
                        observeCustomTabEnrichmentSettings()
                    }
                }
        }
    }

    private fun observeCustomTabEnrichmentSettings() {
        viewModelScope.launch {
            combine(
                customTabTmdbSettingsDataStore.settings,
                customTabMdbListSettingsDataStore.settings
            ) { tmdb, mdb ->
                tmdb to mdb
            }
                .distinctUntilChanged()
                .collectLatest { (tmdb, mdb) ->
                    currentCustomTabTmdbSettings = tmdb
                    currentCustomTabMdbListSettings = mdb
                    lastCustomTabHeroEnrichmentSignature = null
                    enrichCustomTabHeroItemsIfNeeded(_uiState.value.heroItems)
                }
        }
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
                focusedPosterBackdropTrailerPlaybackTarget = snapshot.focusedPosterBackdropTrailerPlaybackTarget
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
        val continueWatchingCardStyle: com.nuvio.tv.domain.model.ContinueWatchingCardStyle = com.nuvio.tv.domain.model.ContinueWatchingCardStyle.CARD,
        val useEpisodeThumbnailsInCw: Boolean = true,
        val blurContinueWatchingNextUp: Boolean = false,
        val posterCardWidthDp: Int = 126,
        val posterCardHeightDp: Int = 189,
        val posterCardCornerRadiusDp: Int = 12,
        val posterLabelsEnabled: Boolean = true,
        val catalogAddonNameEnabled: Boolean = false,
        val focusedPosterBackdropExpandEnabled: Boolean = false,
        val focusedPosterBackdropExpandDelaySeconds: Int = 3,
        val focusedPosterBackdropTrailerEnabled: Boolean = false,
        val focusedPosterBackdropTrailerMuted: Boolean = true,
        val focusedPosterBackdropTrailerPlaybackTarget: com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget = com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    )

    private fun observeCustomTabAddons() {
        viewModelScope.launch {
            animeAddonRepository.getInstalledAnimeAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    lastAddons = addons
                    val enabled = addons.filter { it.enabled }
                    if (enabled.isEmpty()) {
                        rows.clear()
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

    private suspend fun loadAllCatalogs(addons: List<Addon>) {
        _uiState.update {
            it.copy(isLoading = true, error = null, installedAddonsCount = addons.size)
        }
        val tab = _uiState.value.customTab
        rows.clear()
        tab.sources.forEach { source ->
            val addon = addons.find { it.id == source.addonId }
            val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
            catalog?.let { cat ->
                rows[homeCatalogKey(source.addonId, source.type, source.catalogId)] = emptyRow(addon!!, cat, source.genre)
            }
        }
        publishRows()

        val catalogsToLoad = tab.sources.mapNotNull { source ->
            val addon = addons.find { it.id == source.addonId }
            val catalog = addon?.catalogs?.find { it.id == source.catalogId && it.apiType == source.type }
            if (addon != null && catalog != null) addon to catalog else null
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

    private fun emptyRow(addon: Addon, catalog: CatalogDescriptor, genre: String?): CatalogRow {
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
        val key = homeCatalogKey(addon.id, catalog.apiType, catalog.id)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        Log.d(TAG, "Loading custom tab catalog addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id}")
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
                    rows[key] = result.data
                    pendingLoads = (pendingLoads - 1).coerceAtLeast(0)
                    publishRows()
                    if (pendingLoads == 0) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                is NetworkResult.Error -> {
                    val currentRow = rows[key]
                    val errorRow = currentRow?.copy(isLoading = false, items = emptyList())
                    errorRow?.let { rows[key] = it }
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

    fun loadMoreCatalogItems(rowKey: String) {
        val row = rows[rowKey] ?: return
        val nextPage = row.currentPage + 1
        val addon = lastAddons.find { it.id == row.addonId }
        val catalog = addon?.catalogs?.find { it.id == row.catalogId && it.apiType == row.apiType }
        catalog?.let { cat ->
            loadCatalogPage(row, addon!!, cat, nextPage)
        }
    }

    private suspend fun loadCatalogPage(row: CatalogRow, addon: Addon, catalog: CatalogDescriptor, page: Int) {
        val key = homeCatalogKey(addon.id, catalog.apiType, catalog.id)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = page * skipStep,
            skipStep = skipStep,
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val mergedRow = row.mergeCatalogPage(result.data)
                    rows[key] = mergedRow
                    publishRows()
                }
                is NetworkResult.Error -> {
                    val errorRow = row.copy(isLoading = false)
                    rows[key] = errorRow
                    publishRows()
                }
            }
        }
    }

    private fun publishRows() {
        val tab = _uiState.value.customTab
        val orderedRows = tab.sources.mapNotNull { source ->
            rows[homeCatalogKey(source.addonId, source.type, source.catalogId)]
        }
        val sortedRows = if (followAddonsOrder) {
            orderedRows.sortedBy { row ->
                layoutOrderKeys.indexOfFirst { it == homeCatalogKey(row.addonId, row.apiType, row.catalogId) }
            }
        } else {
            orderedRows
        }
        _uiState.update { it.copy(rows = sortedRows) }
    }

    private fun observeCustomTabContinueWatching() {
        // TODO: Implement continue watching observation similar to AnimeHomeViewModel
        // For now, keep basic implementation
    }

    private fun enrichCustomTabHeroItemsIfNeeded(heroItems: List<MetaPreview>) {
        // TODO: Implement hero enrichment similar to AnimeHomeViewModel
    }

    internal suspend fun enrichCustomTabHeroItem(item: MetaPreview): MetaPreview? {
        // TODO: Implement hero item enrichment similar to AnimeHomeViewModel
        return item
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        // TODO: Implement remove continue watching similar to AnimeHomeViewModel
    }
}