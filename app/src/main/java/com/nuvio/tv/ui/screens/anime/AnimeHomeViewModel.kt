package com.nuvio.tv.ui.screens.anime

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private val animeAddonRepository: AnimeAddonRepository,
    private val catalogRepository: CatalogRepository,
    @Named("anime_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    companion object {
        private const val TAG = "AnimeHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    private val _uiState = MutableStateFlow(AnimeHomeUiState())
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

    init {
        observeLayoutPreferences()
        observeAnimeAddons()
    }

    private fun observeLayoutPreferences() {
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.heroCatalogSelections,
                layoutPreferenceDataStore.heroSectionEnabled
            ) { orderKeys, disabledKeys, heroKeys, heroEnabled ->
                LayoutSnapshot(orderKeys, disabledKeys.toSet(), heroKeys, heroEnabled)
            }.distinctUntilChanged().collectLatest { snapshot ->
                layoutOrderKeys.clear()
                layoutOrderKeys.addAll(snapshot.orderKeys)
                layoutDisabledKeys.clear()
                layoutDisabledKeys.addAll(snapshot.disabledKeys)
                heroCatalogKeys.clear()
                heroCatalogKeys.addAll(snapshot.heroKeys)
                heroSectionEnabled = snapshot.heroEnabled
                publishRows()
            }
        }
    }

    private data class LayoutSnapshot(
        val orderKeys: List<String>,
        val disabledKeys: Set<String>,
        val heroKeys: List<String>,
        val heroEnabled: Boolean
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

    private fun emptyRow(addon: Addon, catalog: CatalogDescriptor): CatalogRow =
        CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.type,
            rawType = catalog.apiType,
            items = emptyList(),
            isLoading = true,
            hasMore = false,
            supportsSkip = catalog.supportsExtra("skip"),
            skipStep = catalog.skipStep()
        )

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
                    synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false) }
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
        if (existing != null && existing.items.isNotEmpty()) return

        val addon = lastAddons.firstOrNull { it.id == addonId } ?: return
        val catalog = addon.catalogs.firstOrNull { it.apiType == type && it.id == catalogId } ?: return
        if (!shouldShowCatalog(catalog)) return

        viewModelScope.launch {
            catalogLoadMutex.withLock {
                val current = synchronized(rows) { rows[key] }
                if (current != null && current.items.isNotEmpty()) return@withLock
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
        val filtered = ordered.filter { it.items.isNotEmpty() }
        val heroRow = computeHeroRow(filtered)
        _uiState.update { state ->
            val updated = state.copy(
                rows = filtered,
                heroItem = heroRow?.items?.firstOrNull(),
                heroAddonBaseUrl = heroRow?.addonBaseUrl
            )
            if (updated == state) state else updated
        }
    }

    private fun orderRows(all: List<CatalogRow>): List<CatalogRow> {
        val enabled = all.filterNot {
            layoutDisabledKeys.contains(homeCatalogKey(it.addonId, it.rawType, it.catalogId))
        }
        if (layoutOrderKeys.isEmpty()) return enabled
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
        val byKey = rows.associateBy { homeCatalogKey(it.addonId, it.rawType, it.catalogId) }
        for (key in heroCatalogKeys) {
            byKey[key]?.let { return it }
        }
        return rows.firstOrNull()
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
