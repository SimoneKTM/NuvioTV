package com.nuvio.tv.ui.screens.extra

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.AddonInfo
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.core.server.CatalogInfo
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.PageState
import com.nuvio.tv.core.server.PendingAddonChange
import com.nuvio.tv.core.server.collectionsToServerFormat
import com.nuvio.tv.core.sync.CollectionSyncService
import com.nuvio.tv.core.sync.HomeCatalogSettingsSyncService
import com.nuvio.tv.core.sync.homeCatalogKey
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.addon.PendingChangeInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named

data class ExtraSettingsUiState(
    val addons: List<Addon> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val installUrl: String = "",
    val isInstalling: Boolean = false,
    val error: String? = null,
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val pendingChange: PendingChangeInfo? = null
)

@HiltViewModel
class ExtraSettingsViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    @Named("extra_layout") private val extraLayoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val collectionsDataStore: CollectionsDataStore,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtraSettingsUiState())
    val uiState: StateFlow<ExtraSettingsUiState> = _uiState.asStateFlow()

    private var server: AddonConfigServer? = null
    private var logoBytes: ByteArray? = null
    private var extraCatalogOrderKeys: List<String> = emptyList()
    private var extraDisabledCatalogKeys: Set<String> = emptySet()
    private var extraFollowAddonsOrder: Boolean = false
    private var currentCollections: List<Collection> = emptyList()
    private var homeDisabledCatalogKeys: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            addonRepository.getInstalledAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    _uiState.update {
                        it.copy(addons = addons, isLoading = false)
                    }
                }
        }
        observeCatalogPreferences()
        loadLogoBytes()
    }

    private fun observeCatalogPreferences() {
        viewModelScope.launch {
            combine(
                extraLayoutPreferenceDataStore.homeCatalogOrderKeys,
                extraLayoutPreferenceDataStore.disabledHomeCatalogKeys,
                extraLayoutPreferenceDataStore.followAddonsOrder,
                collectionsDataStore.collections,
                layoutPreferenceDataStore.disabledHomeCatalogKeys
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val orderKeys = values[0] as List<String>
                @Suppress("UNCHECKED_CAST")
                val disabledKeys = (values[1] as List<String>).toSet()
                @Suppress("UNCHECKED_CAST")
                val followAddonsOrder = values[2] as Boolean
                @Suppress("UNCHECKED_CAST")
                val collections = values[3] as List<Collection>
                @Suppress("UNCHECKED_CAST")
                val homeDisabled = (values[4] as List<String>).toSet()
                List5(orderKeys, disabledKeys, followAddonsOrder, collections, homeDisabled)
            }.collectLatest { state ->
                extraCatalogOrderKeys = state.orderKeys
                extraDisabledCatalogKeys = state.disabledKeys
                extraFollowAddonsOrder = state.followAddonsOrder
                currentCollections = state.collections
                homeDisabledCatalogKeys = state.homeDisabled
            }
        }
    }

    private data class List5(
        val orderKeys: List<String>,
        val disabledKeys: Set<String>,
        val followAddonsOrder: Boolean,
        val collections: List<Collection>,
        val homeDisabled: Set<String>
    )

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
    }

    fun onInstallUrlChange(value: String) {
        _uiState.update { it.copy(installUrl = value, error = null) }
    }

    fun installAddon() {
        val url = _uiState.value.installUrl.trim()
        if (url.isEmpty()) return
        _uiState.update { it.copy(isInstalling = true, error = null) }
        viewModelScope.launch {
            when (val result = addonRepository.fetchAddon(url)) {
                is NetworkResult.Success -> {
                    addonRepository.addAddon(url)
                    _uiState.update { it.copy(isInstalling = false, installUrl = "") }
                    Log.d("ExtraSettingsViewModel", "Installed addon url=$url")
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            error = result.message ?: context.getString(R.string.addon_install_error, result.code.toString())
                        )
                    }
                }
                NetworkResult.Loading -> { }
            }
        }
    }

    fun removeAddon(url: String) {
        viewModelScope.launch {
            addonRepository.removeAddon(url)
        }
    }

    fun moveAddonUp(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.addons
            val index = current.indexOfFirst { it.baseUrl == url }
            if (index <= 0) return@launch
            val reordered = current.toMutableList()
            reordered.removeAt(index)
            reordered.add(index - 1, current[index])
            addonRepository.setAddonOrder(reordered.map { it.baseUrl })
        }
    }

    fun moveAddonDown(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.addons
            val index = current.indexOfFirst { it.baseUrl == url }
            if (index == -1 || index >= current.lastIndex) return@launch
            val reordered = current.toMutableList()
            reordered.removeAt(index)
            reordered.add(index + 1, current[index])
            addonRepository.setAddonOrder(reordered.map { it.baseUrl })
        }
    }

    fun setAddonEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            addonRepository.setAddonEnabled(url, enabled)
        }
    }

    fun refreshAddons() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            try {
                addonRepository.getInstalledAddons().first()
            } catch (e: Exception) {
                Log.e("ExtraSettingsViewModel", "Failed to refresh addons", e)
                _uiState.update { it.copy(error = context.getString(R.string.extra_settings_refresh_failed)) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(error = context.getString(R.string.error_network_required)) }
            return
        }

        stopServerInternal()

        server = AddonConfigServer.startOnAvailablePort(
            context = context,
            webConfigMode = AddonWebConfigMode.ADDONS_ONLY,
            currentPageStateProvider = { buildPageState() },
            onChangeProposed = { change -> handleChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            _uiState.update { it.copy(error = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                error = null
            )
        }
    }

    fun stopQrMode() {
        stopServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingChange = null
            )
        }
    }

    private fun buildPageState(): PageState {
        val addons = _uiState.value.addons
        val orderedCatalogs = buildExtraCatalogEntries(
            addons = addons.enabledAddons(),
            savedOrderKeys = extraCatalogOrderKeys,
            disabledKeys = extraDisabledCatalogKeys
        )
        val catalogInfos = orderedCatalogs.map { catalog ->
            CatalogInfo(
                key = catalog.key,
                disableKey = catalog.key,
                catalogName = catalog.catalogName,
                addonName = catalog.addonName,
                type = catalog.typeLabel,
                isDisabled = catalog.isDisabled,
                animeAddon = false
            )
        }
        val collectionInfos = currentCollections.map { col ->
            val colKey = "collection_${col.id}"
            CatalogInfo(
                key = colKey,
                disableKey = colKey,
                catalogName = col.title,
                addonName = "${col.folders.size} folder${if (col.folders.size != 1) "s" else ""}",
                type = "collection",
                isDisabled = colKey in homeDisabledCatalogKeys
            )
        }

        val unifiedCatalogs: List<CatalogInfo>
        if (extraFollowAddonsOrder) {
            val addonKeys = catalogInfos.map { it.key }
            val collectionKeysSet = collectionInfos.map { it.key }.toSet()
            val catalogByKey = (catalogInfos + collectionInfos).associateBy { it.key }
            val savedValid = extraCatalogOrderKeys.filter { it in catalogByKey }.distinct()

            if (savedValid.isNotEmpty()) {
                val result = mutableListOf<String>()
                var addonPointer = 0
                for (savedKey in savedValid) {
                    if (savedKey in collectionKeysSet) {
                        result.add(savedKey)
                    } else {
                        val targetIdx = addonKeys.indexOf(savedKey)
                        if (targetIdx >= 0) {
                            while (addonPointer <= targetIdx) {
                                val ak = addonKeys[addonPointer]
                                if (ak !in result) result.add(ak)
                                addonPointer++
                            }
                        }
                    }
                }
                while (addonPointer < addonKeys.size) {
                    val ak = addonKeys[addonPointer]
                    if (ak !in result) result.add(ak)
                    addonPointer++
                }
                for (ck in collectionKeysSet) {
                    if (ck !in result) result.add(ck)
                }
                unifiedCatalogs = result.mapNotNull { catalogByKey[it] }
            } else {
                unifiedCatalogs = catalogInfos + collectionInfos
            }
        } else {
            val catalogByKey = (catalogInfos + collectionInfos).associateBy { it.key }
            val savedOrder = extraCatalogOrderKeys
            val orderedKeys = savedOrder.filter { it in catalogByKey }
            val unseenKeys = catalogByKey.keys - orderedKeys.toSet()
            unifiedCatalogs = (orderedKeys + unseenKeys).mapNotNull { catalogByKey[it] }
        }

        return PageState(
            addons = addons.map { addon ->
                AddonInfo(
                    url = addon.baseUrl,
                    name = addon.displayName.ifBlank { addon.baseUrl },
                    description = addon.description
                )
            },
            catalogs = unifiedCatalogs,
            collections = collectionsToServerFormat(currentCollections),
            disabledCollectionKeys = homeDisabledCatalogKeys
                .filter { it.startsWith("collection_") },
            followAddonsOrder = extraFollowAddonsOrder
        )
    }

    private fun handleChangeProposed(change: PendingAddonChange) {
        val currentUrls = _uiState.value.addons.map { it.baseUrl }
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()
        val currentNormalized = currentUrls.map { normalizeUrlForComparison(it) }.toSet()

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentNormalized }
        val removed = currentUrls.filter { normalizeUrlForComparison(it) !in proposedNormalized }

        val currentNameMap = _uiState.value.addons.associateBy(
            { normalizeUrlForComparison(it.baseUrl) },
            { it.displayName }
        )
        val removedNames = removed.associateWith { url ->
            currentNameMap[normalizeUrlForComparison(url)] ?: url
        }

        val currentCatalogEntries = buildExtraCatalogEntries(
            addons = _uiState.value.addons.enabledAddons(),
            savedOrderKeys = extraCatalogOrderKeys,
            disabledKeys = extraDisabledCatalogKeys
        )
        val availableCatalogKeys = currentCatalogEntries.map { it.key }.toSet()
        val collectionKeysSet = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeysSet
        val availableDisableKeyToName = currentCatalogEntries.associate { entry ->
            entry.key to "${entry.catalogName} • ${entry.addonName}"
        }

        val resolvedProposedCatalogOrderKeys = if (change.proposedCatalogOrderKeys.isEmpty()) {
            currentCatalogEntries.map { it.key }
        } else {
            change.proposedCatalogOrderKeys
                .asSequence()
                .filter { it in allValidOrderKeys }
                .distinct()
                .toList()
        }
        val currentDisabledCatalogKeys = currentCatalogEntries
            .filter { it.isDisabled }
            .map { it.key }
            .toSet()
        val resolvedProposedDisabledCatalogKeys = if (change.proposedDisabledCatalogKeys.isEmpty()) {
            currentDisabledCatalogKeys.toList()
        } else {
            change.proposedDisabledCatalogKeys
                .asSequence()
                .filter { it in availableDisableKeyToName }
                .distinct()
                .toList()
        }
        val proposedDisabledSet = resolvedProposedDisabledCatalogKeys.toSet()
        val newlyDisabledCatalogs = (proposedDisabledSet - currentDisabledCatalogKeys)
            .mapNotNull { availableDisableKeyToName[it] }
        val newlyEnabledCatalogs = (currentDisabledCatalogKeys - proposedDisabledSet)
            .mapNotNull { availableDisableKeyToName[it] }
        val catalogsReordered = resolvedProposedCatalogOrderKeys != currentCatalogEntries.map { it.key }

        val proposedCollectionsJson = change.proposedCollectionsJson
        val collectionsChanged = proposedCollectionsJson != null
        val proposedCollectionCount = if (proposedCollectionsJson != null) {
            try { parseCollectionsFromJson(proposedCollectionsJson).size } catch (_: Exception) { 0 }
        } else 0

        _uiState.update {
            it.copy(
                pendingChange = PendingChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    proposedCatalogOrderKeys = resolvedProposedCatalogOrderKeys,
                    proposedDisabledCatalogKeys = resolvedProposedDisabledCatalogKeys,
                    addedUrls = added,
                    removedUrls = removed,
                    catalogsReordered = catalogsReordered,
                    disabledCatalogNames = newlyDisabledCatalogs,
                    enabledCatalogNames = newlyEnabledCatalogs,
                    removedNames = removedNames,
                    collectionsChanged = collectionsChanged,
                    proposedCollectionsJson = proposedCollectionsJson,
                    proposedCollectionCount = proposedCollectionCount,
                    proposedDisabledCollectionKeys = change.proposedDisabledCollectionKeys,
                    proposedFollowAddonsOrder = change.proposedFollowAddonsOrder
                )
            )
        }

        if (added.isNotEmpty()) {
            viewModelScope.launch {
                val addedNames = withContext(Dispatchers.IO) {
                    added.associateWith { url ->
                        fetchAddonName(url)
                    }
                }
                _uiState.update { state ->
                    val pending = state.pendingChange
                    if (pending == null || pending.changeId != change.id) {
                        state
                    } else {
                        state.copy(
                            pendingChange = pending.copy(addedNames = addedNames)
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchAddonName(url: String): String {
        return try {
            when (val result = addonRepository.fetchAddon(url)) {
                is NetworkResult.Success -> result.data.displayName.ifBlank { url }
                else -> url
            }
        } catch (_: Exception) {
            url
        }
    }

    fun confirmPendingChange() {
        val pending = _uiState.value.pendingChange ?: return

        _uiState.update { it.copy(pendingChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            val currentUrls = _uiState.value.addons.map { it.baseUrl }
            val proposedNormalized = pending.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()

            currentUrls
                .filter { normalizeUrlForComparison(it) !in proposedNormalized }
                .forEach { addonRepository.removeAddon(it) }
            pending.proposedUrls
                .filter { url -> currentUrls.none { normalizeUrlForComparison(it) == normalizeUrlForComparison(url) } }
                .forEach { addonRepository.addAddon(it) }
            addonRepository.setAddonOrder(pending.proposedUrls)

            applyExtraCatalogPreferencesFromPending(pending, pending.proposedUrls)

            if (pending.collectionsChanged && pending.proposedCollectionsJson != null) {
                try {
                    val newCollections = parseCollectionsFromJson(pending.proposedCollectionsJson)
                    collectionsDataStore.setCollections(newCollections)
                    collectionSyncService.triggerPush()
                } catch (_: Exception) { }
            }
            if (pending.proposedDisabledCollectionKeys.isNotEmpty() || homeDisabledCatalogKeys.any { it.startsWith("collection_") }) {
                val nonCollectionDisabledKeys = homeDisabledCatalogKeys.filter { !it.startsWith("collection_") }
                val mergedDisabledKeys = nonCollectionDisabledKeys + pending.proposedDisabledCollectionKeys
                layoutPreferenceDataStore.setDisabledHomeCatalogKeys(mergedDisabledKeys)
                homeCatalogSettingsSyncService.triggerPush()
            }
            if (pending.proposedFollowAddonsOrder != null) {
                extraLayoutPreferenceDataStore.setFollowAddonsOrder(pending.proposedFollowAddonsOrder)
            }

            server?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingChange = null) }

            delay(2500)

            stopServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    private suspend fun applyExtraCatalogPreferencesFromPending(
        pending: PendingChangeInfo,
        validUrls: List<String>
    ) {
        val validUrlSet = validUrls.map { normalizeUrlForComparison(it) }.toSet()
        val targetAddons = _uiState.value.addons.filter { addon ->
            addon.enabled && normalizeUrlForComparison(addon.baseUrl) in validUrlSet
        }
        val availableCatalogEntries = buildExtraCatalogEntries(
            addons = targetAddons,
            savedOrderKeys = extraCatalogOrderKeys,
            disabledKeys = extraDisabledCatalogKeys
        )
        val availableCatalogKeys = availableCatalogEntries.map { it.key }.toSet()
        val collectionKeys = currentCollections.map { "collection_${it.id}" }.toSet()
        val allValidOrderKeys = availableCatalogKeys + collectionKeys

        val validCatalogOrder = pending.proposedCatalogOrderKeys
            .asSequence()
            .filter { it in allValidOrderKeys }
            .distinct()
            .toList()
        val validDisabledCatalogs = pending.proposedDisabledCatalogKeys
            .asSequence()
            .filter { it in availableCatalogKeys }
            .distinct()
            .toList()

        extraLayoutPreferenceDataStore.setHomeCatalogOrderKeys(validCatalogOrder)
        extraLayoutPreferenceDataStore.setDisabledHomeCatalogKeys(validDisabledCatalogs)
    }

    private fun buildExtraCatalogEntries(
        addons: List<Addon>,
        savedOrderKeys: List<String>,
        disabledKeys: Set<String>
    ): List<ExtraQrCatalogEntry> {
        val defaultEntries = buildDefaultExtraCatalogEntries(addons)
        val entryByKey = defaultEntries.associateBy { it.key }
        val defaultOrderKeys = defaultEntries.map { it.key }
        val savedValid = savedOrderKeys
            .asSequence()
            .filter { it in entryByKey }
            .distinct()
            .toList()
        val savedSet = savedValid.toSet()
        val effectiveOrder = savedValid + defaultOrderKeys.filterNot { it in savedSet }

        return effectiveOrder.mapNotNull { key ->
            val entry = entryByKey[key] ?: return@mapNotNull null
            entry.copy(
                isDisabled = entry.key in disabledKeys
            )
        }
    }

    private fun buildDefaultExtraCatalogEntries(addons: List<Addon>): List<ExtraQrCatalogEntry> {
        val entries = mutableListOf<ExtraQrCatalogEntry>()
        val seenKeys = mutableSetOf<String>()

        addons.forEach { addon ->
            addon.catalogs
                .filterNot { it.isSearchOnlyCatalog() }
                .forEach { catalog ->
                    val key = homeCatalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    if (seenKeys.add(key)) {
                        entries.add(
                            ExtraQrCatalogEntry(
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

    private fun parseCollectionsFromJson(json: String): List<Collection> {
        return collectionsDataStore.importFromJson(json)
    }

    private data class ExtraQrCatalogEntry(
        val key: String,
        val catalogName: String,
        val addonName: String,
        val typeLabel: String,
        val isDisabled: Boolean = false
    )

    fun rejectPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingChange = null) }
    }

    private fun normalizeUrlForComparison(url: String): String =
        url.trim().trimEnd('/').lowercase()

    private fun stopServerInternal() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServerInternal()
        super.onCleared()
    }
}
