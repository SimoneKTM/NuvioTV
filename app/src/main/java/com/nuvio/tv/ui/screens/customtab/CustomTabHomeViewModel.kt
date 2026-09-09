package com.nuvio.tv.ui.screens.customtab

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
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
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: MetaRepository,
    private val watchProgressRepository: WatchProgressRepository,
    @Named("anime_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
) : ViewModel() {

    companion object {
        private const val TAG = "CustomTabHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    internal val _uiState = MutableStateFlow(CustomTabHomeUiState())

    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val rows = LinkedHashMap<String, CatalogRow>()
    private val catalogLoadMutex = Mutex()
    private var pendingLoads = 0
    private var lastAddons: List<com.nuvio.tv.domain.model.Addon> = emptyList()

    private val layoutOrderKeys = mutableListOf<String>()
    private val layoutDisabledKeys = mutableSetOf<String>()
    private val heroCatalogKeys = mutableListOf<String>()
    private var heroSectionEnabled = true

    val uiState: StateFlow<CustomTabHomeUiState> = _uiState.asStateFlow()

    private var currentTabId: String? = null
    private var currentTab: CustomTab? = null

    init {
        observeLayoutPreferences()
    }

    fun setTabId(tabId: String) {
        if (currentTabId == tabId) return
        currentTabId = tabId
        viewModelScope.launch {
            loadTabConfig()
        }
    }

    private fun loadTabConfig() {
        currentTabId?.let { tabId ->
            viewModelScope.launch {
                layoutPreferenceDataStore.customTabs.first().let { tabs ->
                    val tab = tabs.find { it.id == tabId }
                    if (tab != null) {
                        currentTab = tab
                        applyTabConfig(tab)
                        observeCustomAddons()
                        observeCustomContinueWatching()
                    }
                }
            }
        }
    }

    private fun applyTabConfig(tab: CustomTab) {
        val config = tab.config
        _uiState.update { state ->
            state.copy(
                tabId = tab.id,
                displayName = tab.displayName,
                homeLayout = config.homeLayout,
                catalogTypeSuffixEnabled = config.catalogTypeSuffix,
                hideUnreleasedContent = config.hideUnreleasedContent,
                modernLandscapePostersEnabled = config.modernLandscapePosters,
                modernHeroFullScreenBackdropEnabled = config.heroFullScreenBackdrop,
                classicFocusGradientEnabled = config.classicFocusGradient,
                continueWatchingCardStyle = config.continueWatchingCardStyle,
                useEpisodeThumbnailsInCw = config.useEpisodeThumbnailsInCw,
                blurContinueWatchingNextUp = config.blurContinueWatchingNextUp,
                posterCardWidthDp = config.posterCardWidthDp,
                posterCardHeightDp = config.posterCardHeightDp,
                posterCardCornerRadiusDp = config.posterCardCornerRadiusDp,
                posterLabelsEnabled = config.posterLabels,
                catalogAddonNameEnabled = config.catalogAddonName,
                focusedPosterBackdropExpandEnabled = config.focusedPosterBackdropExpand,
                focusedPosterBackdropExpandDelaySeconds = config.focusedPosterBackdropExpandDelay,
                focusedPosterBackdropTrailerEnabled = config.focusedPosterBackdropTrailer,
                focusedPosterBackdropTrailerMuted = config.focusedPosterBackdropTrailerMuted
            )
        }
        loadCustomCatalogs(config.selectedAddons)
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            layoutPreferenceDataStore.customTabs
                .distinctUntilChanged()
                .collectLatest { tabs ->
                    currentTabId?.let { tabId ->
                        val tab = tabs.find { it.id == tabId }
                        tab?.let {
                            if (currentTab?.config != it.config) {
                                applyTabConfig(it)
                            }
                        }
                    }
                }
        }
    }

    private fun observeCustomAddons() {
        viewModelScope.launch {
            combine(
                addonRepository.getInstalledAddons(),
                layoutPreferenceDataStore.customTabs
            ) { addons, tabs ->
                val tab = tabs.find { it.id == currentTabId }
                tab?.let { currentTab ->
                    val selectedAddonIds = currentTab.config.selectedAddons
                    val filteredAddons = if (selectedAddonIds.isNotEmpty()) {
                        addons.filter { it.id in selectedAddonIds }
                    } else {
                        addons
                    }
                    filteredAddons to currentTab
                } ?: emptyList<com.nuvio.tv.domain.model.Addon>() to currentTab!!
            }
                .distinctUntilChanged()
                .collectLatest { (addons, tab) ->
                    if (addons != lastAddons) {
                        lastAddons = addons
                        loadCustomCatalogs(tab.config.selectedAddons)
                    }
                }
        }
    }

    private fun loadCustomCatalogs(selectedAddonIds: List<String>) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val allAddons = addonRepository.getInstalledAddons().first()
            val filteredAddons = if (selectedAddonIds.isNotEmpty()) {
                allAddons.filter { it.id in selectedAddonIds }
            } else {
                allAddons
            }

            val customTitles = currentTab?.config?.customCatalogTitles ?: emptyMap()
            val disabledCatalogs = currentTab?.config?.disabledCatalogs ?: emptyList()

            val catalogsToLoad = filteredAddons.flatMap { addon ->
                addon.catalogs
                    .filterNot { catalog ->
                        disabledCatalogs.contains(catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id))
                    }
                    .map { catalog -> addon to catalog }
            }

            val rows = catalogsToLoad.map { (addon, catalog) ->
                val customTitle = customTitles[catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)] ?: catalog.name
                CatalogRow(
                    catalogId = catalog.id,
                    catalogName = customTitle,
                    addonId = addon.id,
                    addonBaseUrl = addon.baseUrl,
                    addonName = addon.name,
                    apiType = catalog.apiType,
                    rawType = catalog.apiType,
                    items = emptyList(),
                    hasMore = true,
                    isLoading = true
                )
            }

            _uiState.update { state ->
                state.copy(
                    rows = rows,
                    installedAddonsCount = filteredAddons.size,
                    isLoading = rows.isEmpty()
                )
            }

            rows.forEach { row ->
                loadCustomCatalogItems(row)
            }
        }
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String =
        "catalog_$addonId|$type|$catalogId"

    private fun loadCustomCatalogItems(row: CatalogRow) {
        val job = viewModelScope.launch {
            try {
                catalogRepository.getCatalog(
                    addonBaseUrl = row.addonBaseUrl,
                    addonId = row.addonId,
                    addonName = row.addonName,
                    catalogId = row.catalogId,
                    catalogName = row.catalogName,
                    type = row.apiType,
                    skip = 0,
                    skipStep = 100,
                    supportsSkip = false
                ).collect { result ->
                    when (result) {
                        is com.nuvio.tv.core.network.NetworkResult.Success -> {
                            val catalogRow = result.data
                            val enrichedItems = catalogRow.items.map { item ->
                                item.copy(
                                    poster = item.poster ?: item.backdropUrl ?: PLACEHOLDER_IMAGE_URL,
                                    posterShape = item.posterShape
                                )
                            }
                            val updatedRows = _fullCatalogRows.value.map { r ->
                                if (r.stableKey() == row.stableKey()) {
                                    r.copy(items = enrichedItems, hasMore = enrichedItems.size >= 20, isLoading = false)
                                } else r
                            }
                            _fullCatalogRows.value = updatedRows

                            _uiState.update { state ->
                                val updatedRows = state.rows.map { r ->
                                    if (r.stableKey() == row.stableKey()) {
                                        r.copy(items = enrichedItems, hasMore = enrichedItems.size >= 20, isLoading = false)
                                    } else r
                                }
                                state.copy(
                                    rows = updatedRows,
                                    isLoading = updatedRows.any { it.isLoading }
                                )
                            }
                        }
                        is com.nuvio.tv.core.network.NetworkResult.Error -> {
                            Log.e(TAG, "Error loading catalog items for ${row.catalogId}: ${result.error}")
                            _fullCatalogRows.update { fullRows ->
                                fullRows.map { r ->
                                    if (r.stableKey() == row.stableKey()) r.copy(isLoading = false) else r
                                }
                            }
                            _uiState.update { state ->
                                val updatedRows = state.rows.map { r ->
                                    if (r.stableKey() == row.stableKey()) r.copy(isLoading = false) else r
                                }
                                state.copy(
                                    rows = updatedRows,
                                    isLoading = updatedRows.any { it.isLoading }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnLoadMoreCatalog -> {
                val row = _fullCatalogRows.value.find { it.stableKey() == event.catalogKey }
                row?.let { loadMoreCatalogItems(it) }
            }
        }
    }

    private fun loadMoreCatalogItems(row: CatalogRow) {
        viewModelScope.launch {
            try {
                catalogRepository.getCatalog(
                    addonBaseUrl = row.addonBaseUrl,
                    addonId = row.addonId,
                    addonName = row.addonName,
                    catalogId = row.catalogId,
                    catalogName = row.catalogName,
                    type = row.apiType,
                    skip = 0,
                    skipStep = 100,
                    supportsSkip = false
                ).collect { result ->
                    when (result) {
                        is com.nuvio.tv.core.network.NetworkResult.Success -> {
                            val catalogRow = result.data
                            val enrichedItems = catalogRow.items.map { item ->
                                item.copy(
                                    poster = item.poster ?: item.backdropUrl ?: PLACEHOLDER_IMAGE_URL,
                                    posterShape = item.posterShape
                                )
                            }
                            val existingItems = _fullCatalogRows.value.find { it.stableKey() == row.stableKey() }?.items?.toMutableList() ?: mutableListOf()
                            val newItems = catalogRow.items.filter { newItem -> !existingItems.any { it.id == newItem.id } }
                            existingItems.addAll(newItems)

                            _fullCatalogRows.update { fullRows ->
                                fullRows.map { r ->
                                    if (r.stableKey() == row.stableKey()) {
                                        r.copy(items = existingItems, hasMore = newItems.size >= 20, isLoading = false)
                                    } else r
                                }
                            }
                            _uiState.update { state ->
                                val updatedRows = state.rows.map { r ->
                                    if (r.stableKey() == row.stableKey()) {
                                        r.copy(items = existingItems, hasMore = newItems.size >= 20, isLoading = false)
                                    } else r
                                }
                                state.copy(rows = updatedRows, isLoading = updatedRows.any { it.isLoading })
                            }
                        }
                        is com.nuvio.tv.core.network.NetworkResult.Error -> {
                            Log.e(TAG, "Error loading more items for ${row.catalogId}: ${result.error}")
                        }
                    }
                }
            }
        }
    }

    private fun observeCustomContinueWatching() {
        viewModelScope.launch {
            watchProgressRepository.getAllProgress().first().collectLatest { progressList ->
                val cwItems = progressList.map { com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress(it) }
                _uiState.update { it.copy(continueWatchingItems = cwItems) }
            }
        }
    }

    // Methods required by HomeRouteComponents (duck typing)
    val focusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState()).asStateFlow()
    val gridFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState()).asStateFlow()
    val scrollToTopTrigger: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
    val trailerPreviewUrls: Map<String, String> = emptyMap()
    val trailerPreviewAudioUrls: Map<String, String> = emptyMap()
    val enrichingItemId: StateFlow<String?> = MutableStateFlow(null).asStateFlow()
    val lastEnrichedPreview: StateFlow<MetaPreview?> = MutableStateFlow(null).asStateFlow()
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = MutableStateFlow(emptyMap()).asStateFlow()
    val failedEnrichmentIds: StateFlow<Set<String>> = MutableStateFlow(emptySet()).asStateFlow()

    fun requestTrailerPreview(item: MetaPreview) {
        viewModelScope.launch {
            val trailer = metaRepository.getTrailer(item.id, item.apiType).firstOrNull()
            trailer?.let { t ->
                // Custom tabs don't have trailer preview URLs state
            }
        }
    }

    fun onItemFocus(item: MetaPreview) {
        // Custom tabs don't need complex focus handling
    }

    fun saveFocusState(
        vi: Int, vo: Int, rk: String?, ikm: Map<String, String>,
        m: Map<String, Int>, ri: Int, ii: Int
    ) {
        // Not used for custom tabs
    }

    fun saveGridFocusState(vi: Int, vo: Int, focusedItemKey: String) {
        // Not used for custom tabs
    }

    fun requestLazyCatalogLoad(catalogKey: String) {
        val row = _fullCatalogRows.value.find { it.stableKey() == catalogKey }
        row?.let { loadCustomCatalogItems(it) }
    }

    fun preloadAdjacentItem(item: MetaPreview) {
        // Not used for custom tabs
    }
}