package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AnimeCatalogOrderViewModel @Inject constructor(
    private val animeAddonRepository: AnimeAddonRepository,
    private val profileManager: ProfileManager,
    @Named("anime_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogOrderUiState())
    val uiState: StateFlow<CatalogOrderUiState> = _uiState.asStateFlow()
    private var disabledKeysCache: Set<String> = emptySet()

    // Read-only profiles share the primary profile's addons: their catalog
    // settings live in the primary file, so reordering here must be inert.
    private val isReadOnly: Boolean
        get() = AddonManagementAccess.isReadOnly(profileManager.activeProfile)

    init {
        observeCatalogs()
    }

    fun moveUp(key: String) {
        if (isReadOnly) return
        if (_uiState.value.followAddonsOrder) return
        moveCatalog(key, -1)
    }

    fun moveDown(key: String) {
        if (isReadOnly) return
        if (_uiState.value.followAddonsOrder) return
        moveCatalog(key, 1)
    }

    fun toggleFollowAddonsOrder(enabled: Boolean) {
        if (isReadOnly) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setFollowAddonsOrder(enabled)
        }
    }

    fun toggleCatalogEnabled(disableKey: String) {
        if (isReadOnly) return
        viewModelScope.launch {
            // Merge into the stored set instead of rewriting from the visible
            // rows only — otherwise disabled keys for currently hidden or
            // uninstalled catalogs get wiped by any toggle.
            val stored = layoutPreferenceDataStore.disabledHomeCatalogKeys.first().toMutableSet()
            if (disableKey in stored) stored.remove(disableKey) else stored.add(disableKey)
            layoutPreferenceDataStore.setDisabledHomeCatalogKeys(stored.toList())
        }
    }

    private fun moveCatalog(key: String, direction: Int) {
        val currentKeys = _uiState.value.items.map { it.key }
        val currentIndex = currentKeys.indexOf(key)
        if (currentIndex == -1) return

        val newIndex = currentIndex + direction
        if (newIndex !in currentKeys.indices) return

        val reordered = currentKeys.toMutableList().apply {
            val item = removeAt(currentIndex)
            add(newIndex, item)
        }

        viewModelScope.launch {
            val stored = layoutPreferenceDataStore.homeCatalogOrderKeys.first()
            layoutPreferenceDataStore.setHomeCatalogOrderKeys(
                spliceVisibleOrder(stored = stored, visible = currentKeys, reordered = reordered)
            )
        }
    }

    /**
     * Writes only the visible rows back in their new relative order while keys
     * not on this screen (hidden/uninstalled catalogs) keep their stored slot.
     */
    private fun spliceVisibleOrder(
        stored: List<String>,
        visible: List<String>,
        reordered: List<String>
    ): List<String> {
        val visibleSet = visible.toSet()
        val result = ArrayList<String>(stored.size + reordered.size)
        var next = 0
        for (key in stored) {
            if (key in visibleSet) {
                if (next < reordered.size) result.add(reordered[next++])
            } else {
                result.add(key)
            }
        }
        while (next < reordered.size) result.add(reordered[next++])
        return result
    }

    private fun observeCatalogs() {
        viewModelScope.launch {
            combine(
                animeAddonRepository.getInstalledAnimeAddons(),
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.followAddonsOrder
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val addons = values[0] as List<Addon>
                @Suppress("UNCHECKED_CAST")
                val savedOrderKeys = values[1] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[2] as List<String>).toSet()
                val followAddons = values[3] as Boolean

                val items = buildOrderedCatalogItems(
                    addons = addons.enabledAddons(),
                    savedOrderKeys = savedOrderKeys,
                    disabledKeys = disabledKeys,
                    followAddonsOrder = followAddons
                )
                Pair(items, followAddons)
            }.collectLatest { (orderedItems, followAddons) ->
                disabledKeysCache = orderedItems.filter { it.isDisabled }.map { it.disableKey }.toSet()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = orderedItems,
                        followAddonsOrder = followAddons
                    )
                }
            }
        }
    }

    private fun buildOrderedCatalogItems(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>,
        followAddonsOrder: Boolean = false
    ): List<CatalogOrderItem> {
        val entries = buildDefaultCatalogEntries(addons)
        val availableMap = entries.associateBy { it.key }
        val defaultOrderKeys = entries.map { it.key }

        val effectiveOrder: List<String>
        if (followAddonsOrder) {
            // In follow mode, catalogs stay in manifest order.
            effectiveOrder = defaultOrderKeys
        } else {
            val savedValid = savedOrderKeys
                .asSequence()
                .filter { it in availableMap }
                .distinct()
                .toList()

            val savedKeySet = savedValid.toSet()
            val missing = defaultOrderKeys.filterNot { it in savedKeySet }
            effectiveOrder = savedValid + missing
        }

        return effectiveOrder.mapIndexedNotNull { index, key ->
            val entry = availableMap[key] ?: return@mapIndexedNotNull null

            CatalogOrderItem(
                key = entry.key,
                disableKey = entry.key,
                catalogName = entry.catalogName,
                addonName = entry.addonName,
                typeLabel = entry.typeLabel,
                isDisabled = entry.key in disabledKeys,
                canMoveUp = !followAddonsOrder && index > 0,
                canMoveDown = !followAddonsOrder && index < effectiveOrder.lastIndex
            )
        }
    }

    private fun buildDefaultCatalogEntries(addons: List<Addon>): List<AnimeCatalogOrderEntry> {
        val entries = mutableListOf<AnimeCatalogOrderEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                // Hidden-from-home catalogs aren't on this screen; keep them out
                // so toggling here can't silently target an invisible row.
                .filter { !it.hasExplicitShowInHome || it.showInHome }
                .forEach { catalog ->
                    val key = homeCatalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            AnimeCatalogOrderEntry(
                                key = key,
                                catalogName = catalog.name,
                                addonName = addon.displayName,
                                typeLabel = catalog.apiType
                            )
                        )
                    }
                }
        }

        return entries
    }

    private fun CatalogDescriptor.isSearchOnlyCatalog(): Boolean {
        return extra.any { extra -> extra.name.equals("search", ignoreCase = true) && extra.isRequired }
    }
}

private data class AnimeCatalogOrderEntry(
    val key: String,
    val catalogName: String,
    val addonName: String,
    val typeLabel: String
)
