package com.nuvio.tv.ui.screens.customtab

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.catalogRowStableKey
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
import com.nuvio.tv.ui.screens.home.HomeRouteViewModelInternal
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
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Named

private fun CatalogRow.stableKey(): String = catalogRowStableKey(addonId, addonBaseUrl, apiType, catalogId)

@HiltViewModel
class CustomTabHomeViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val metaRepository: MetaRepository,
    private val watchProgressRepository: WatchProgressRepository,
    @Named("anime_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
) : ViewModel(), HomeRouteViewModelInternal {

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
                layoutPreferenceDataStore.customTabs.firstOrNull().let { tabs ->
                    if (tabs.isNotEmpty()) {
                        val tab = tabs.find { it.id == tabId }
                        if (tab != null) {
                            currentTab = tab
                            applyTabConfig(tab)
                            observeCustomAddons()
                            observeCustomContinueWatching()
                        } else {
                            _uiState.update { it.copy(isLoading = false, error = "Tab non trovato") }
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = "Tab non trovato") }
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
                        if (tab != null) {
                            if (currentTab?.config != tab.config) {
                                applyTabConfig(tab)
                            }
                        } else {
                            _uiState.update { it.copy(isLoading = false, error = "Tab eliminato") }
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
                } ?: emptyList<com.nuvio.tv.domain.model.Addon>() to tab
            }
                .distinctUntilChanged()
                .collectLatest { (addons, tab) ->
                    if (tab == null) {
                        _uiState.update { it.copy(isLoading = false, error = "Tab non trovato") }
                        return@collectLatest
                    }
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
            val allAddons = addonRepository.getInstalledAddons().firstOrNull() ?: return@launch
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
                    type = catalog.type,
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
                            Log.e(TAG, "Error loading catalog items for ${row.catalogId}: ${result.message}")
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
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading catalog items", e)
            }
        }
    }

    override fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OnLoadMoreCatalog -> {
                val row = _fullCatalogRows.value.find { it.stableKey() == event.catalogId }
                row?.let { loadMoreCatalogItems(it) }
            }
            else -> {}
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
                            Log.e(TAG, "Error loading more items for ${row.catalogId}: ${result.message}")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more catalog items", e)
            }
        }
    }

    private fun observeCustomContinueWatching() {
        viewModelScope.launch {
            watchProgressRepository.allProgress.collectLatest { progressList ->
                val cwItems = progressList.map { com.nuvio.tv.ui.screens.home.ContinueWatchingItem.InProgress(it) }
                _uiState.update { it.copy(continueWatchingItems = cwItems) }
            }
        }
    }

    // Methods required by HomeRouteViewModelInternal
    override val focusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState()).asStateFlow()
    override val gridFocusState: StateFlow<com.nuvio.tv.ui.screens.home.HomeScreenFocusState> = MutableStateFlow(com.nuvio.tv.ui.screens.home.HomeScreenFocusState()).asStateFlow()
    override val scrollToTopTrigger: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
    override val trailerPreviewUrls: Map<String, String> = emptyMap()
    override val trailerPreviewAudioUrls: Map<String, String> = emptyMap()
    override val enrichingItemId: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val lastEnrichedPreview: StateFlow<MetaPreview?> = MutableStateFlow<MetaPreview?>(null).asStateFlow()
    override val enrichedPreviews: StateFlow<Map<String, MetaPreview>> = MutableStateFlow(mutableMapOf<String, MetaPreview>()).asStateFlow()
    override val failedEnrichmentIds: StateFlow<Set<String>> = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    override fun requestTrailerPreview(item: MetaPreview) {
        // Custom tabs don't have trailer preview implementation
        // This would require TrailerService and full TMDB integration
    }

    override fun requestTrailerPreview(itemId: String, title: String, releaseInfo: String?, apiType: String) {
        // Not used for custom tabs
    }

    override fun onItemFocus(item: MetaPreview) {
        // Custom tabs don't need complex focus handling
    }

    override fun saveFocusState(
        vi: Int, vo: Int, rk: String?, ikm: Map<String, String>,
        m: Map<String, Int>, ri: Int, ii: Int
    ) {
        // Not used for custom tabs
    }

    override fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int,
        focusedItemIndex: Int,
        focusedItemKey: String?
    ) {
        // Not used for custom tabs
    }

    override fun requestLazyCatalogLoad(catalogKey: String) {
        val row = _fullCatalogRows.value.find { it.stableKey() == catalogKey }
        row?.let { loadCustomCatalogItems(it) }
    }

    override fun preloadAdjacentItem(item: MetaPreview) {
        // Not used for custom tabs
    }
}