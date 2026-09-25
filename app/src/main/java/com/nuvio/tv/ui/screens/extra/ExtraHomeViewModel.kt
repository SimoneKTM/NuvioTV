package com.nuvio.tv.ui.screens.extra

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
import com.nuvio.tv.data.local.ExtraTvdbSettingsDataStore
import com.nuvio.tv.data.repository.MDBListRepository
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.TvdbSettings
import com.nuvio.tv.domain.model.mergeCatalogPage
import com.nuvio.tv.domain.model.nextCatalogSkip
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.CwMetaSummary
import com.nuvio.tv.ui.screens.home.NextUpInfo
import com.nuvio.tv.ui.screens.home.NextUpResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class ExtraHomeViewModel @Inject constructor(
    internal val extraAddonRepository: ExtraAddonRepository,
    private val catalogRepository: CatalogRepository,
    internal val watchProgressRepository: WatchProgressRepository,
    internal val metaRepository: MetaRepository,
    @Named("extra_layout") internal val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    // Tab identity (name/logo/visibility) lives in the default store — that's where
    // ExtraHubViewModel writes it and MainActivity reads it for the drawer.
    internal val defaultLayoutPreferenceDataStore: LayoutPreferenceDataStore,
    @Named("extra_cw_cache") internal val extraCwEnrichmentCache: ContinueWatchingEnrichmentCache,
    @Named("extra_tmdb") internal val extraTmdbSettingsDataStore: TmdbSettingsDataStore,
    @Named("extra_mdblist") internal val extraMdbListSettingsDataStore: MDBListSettingsDataStore,
    internal val extraTvdbSettingsDataStore: ExtraTvdbSettingsDataStore,
    internal val tmdbService: TmdbService,
    internal val tmdbMetadataService: TmdbMetadataService,
    internal val tvdbMetadataService: TvdbMetadataService,
    internal val mdbListRepository: MDBListRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ExtraHomeViewModel"
        private const val MAX_CONCURRENT_CATALOG_LOADS = 4
    }

    internal val _uiState = MutableStateFlow(ExtraHomeUiState())

    internal val extraCwMetaCache = Collections.synchronizedMap(mutableMapOf<String, CwMetaSummary?>())
    internal val extraCwMetaNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val extraCwNextUpResolutionCache =
        Collections.synchronizedMap(mutableMapOf<String, NextUpResolution?>())
    internal val extraCwNextUpNegativeCacheTimestamps = ConcurrentHashMap<String, Long>()
    internal val extraDiscoveredOlderNextUpItems =
        Collections.synchronizedList(mutableListOf<ContinueWatchingItem.NextUp>())
    internal val extraCwLastProcessedNextUpContentIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    internal val extraCwEnrichedNextUpOverlay = ConcurrentHashMap<String, NextUpInfo>()
    internal val extraCwEnrichedInProgressOverlay =
        ConcurrentHashMap<String, ContinueWatchingItem.InProgress>()
    internal var extraCwPipelineJob: Job? = null
    internal var currentExtraTmdbSettings: TmdbSettings = TmdbSettings()
    internal var currentExtraMdbListSettings: MDBListSettings = MDBListSettings()
    internal var currentExtraTvdbSettings: TvdbSettings = TvdbSettings()
    internal var extraHeroEnrichmentJob: Job? = null
    internal var lastExtraHeroEnrichmentSignature: String? = null
    internal var lastExtraHeroEnrichedItems: List<MetaPreview> = emptyList()
    val uiState: StateFlow<ExtraHomeUiState> = _uiState.asStateFlow()

    private val _fullCatalogRows = MutableStateFlow<List<CatalogRow>>(emptyList())
    val fullCatalogRows: StateFlow<List<CatalogRow>> = _fullCatalogRows.asStateFlow()

    private val rows = LinkedHashMap<String, CatalogRow>()
    private val catalogLoadSemaphore = Semaphore(MAX_CONCURRENT_CATALOG_LOADS)
    private val catalogLoadGeneration = AtomicInteger(0)
    private var lastCatalogLoadSignature: String? = null
    private var lastAddons: List<Addon> = emptyList()

    private val layoutOrderKeys = mutableListOf<String>()
    private val layoutDisabledKeys = mutableSetOf<String>()
    private val heroCatalogKeys = mutableListOf<String>()
    private var heroSectionEnabled = true
    private var homeLayout = HomeLayout.MODERN
    private var catalogTypeSuffixEnabled = true
    private var hideUnreleasedContent = false
    private var followAddonsOrder = false
    private var classicFocusGradientEnabled = false
    private var continueWatchingCardStyle = ContinueWatchingCardStyle.CARD
    private var useEpisodeThumbnailsInCw = true
    private var blurContinueWatchingNextUp = false
    private var posterCardWidthDp = 126
    private var posterCardHeightDp = 189
    private var posterCardCornerRadiusDp = 12
    private var posterLabelsEnabled = true
    private var catalogAddonNameEnabled = false
    private var focusedPosterBackdropExpandEnabled = false
    private var focusedPosterBackdropExpandDelaySeconds = 3

    init {
        observeLayoutPreferences()
        observeExtraAddons()
        observeExtraContinueWatching()
        observeExtraEnrichmentSettings()
        observeTabName()
    }

    private fun observeTabName() {
        viewModelScope.launch {
            defaultLayoutPreferenceDataStore.extraTabName.collectLatest { name ->
                _uiState.update { it.copy(extraTabName = name) }
            }
        }
    }

    private fun observeExtraEnrichmentSettings() {
        viewModelScope.launch {
            combine(
                extraTmdbSettingsDataStore.settings,
                extraMdbListSettingsDataStore.settings,
                extraTvdbSettingsDataStore.settings
            ) { tmdb, mdb, tvdb ->
                Triple(tmdb, mdb, tvdb)
            }
                .distinctUntilChanged()
                .collectLatest { (tmdb, mdb, tvdb) ->
                    currentExtraTmdbSettings = tmdb
                    currentExtraMdbListSettings = mdb
                    currentExtraTvdbSettings = tvdb
                    lastExtraHeroEnrichmentSignature = null
                    lastExtraHeroEnrichedItems = emptyList()
                    enrichExtraHeroItemsIfNeeded(_uiState.value.heroItems)
                }
        }
    }

    private fun enrichExtraHeroItemsIfNeeded(heroItems: List<MetaPreview>) {
        val tmdbEnabled = currentExtraTmdbSettings.enabled
        val mdbEnabled = currentExtraMdbListSettings.enabled &&
            currentExtraMdbListSettings.apiKey.isNotBlank()
        val tvdbEnabled = currentExtraTvdbSettings.enabled && currentExtraTvdbSettings.hasApiKey
        if (heroItems.isEmpty() || (!tmdbEnabled && !mdbEnabled && !tvdbEnabled)) {
            lastExtraHeroEnrichmentSignature = null
            lastExtraHeroEnrichedItems = emptyList()
            return
        }
        val signature = extraHeroEnrichmentSignature(heroItems)
        extraHeroEnrichmentJob?.cancel()
        extraHeroEnrichmentJob = viewModelScope.launch {
            if (lastExtraHeroEnrichmentSignature == signature) {
                // Same items already enriched — restore the cached enriched list,
                // because every publishRows() rewrites heroItems with the raw list.
                val cached = lastExtraHeroEnrichedItems
                if (cached.isNotEmpty()) {
                    _uiState.update { state ->
                        if (state.heroItems == cached) {
                            state
                        } else {
                            state.copy(
                                heroItems = cached,
                            )
                        }
                    }
                }
                return@launch
            }
            val enrichedItems = enrichExtraHeroItemsBatch(heroItems)
            lastExtraHeroEnrichmentSignature = signature
            lastExtraHeroEnrichedItems = enrichedItems
            _uiState.update { state ->
                if (state.heroItems == enrichedItems) {
                    state
                } else {
                    state.copy(
                        heroItems = enrichedItems,
                    )
                }
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
                layoutPreferenceDataStore.classicFocusGradientEnabled,
                layoutPreferenceDataStore.continueWatchingCardStyle
            ) { snapshot, focusGradient, cardStyle ->
                snapshot.copy(
                    classicFocusGradientEnabled = focusGradient,
                    continueWatchingCardStyle = cardStyle
                )
            }
            val focusedPosterSnapshotFlow = combine(
                viewSnapshotFlow,
                layoutPreferenceDataStore.focusedPosterBackdropExpandEnabled,
                layoutPreferenceDataStore.focusedPosterBackdropExpandDelaySeconds
            ) { snapshot, backdropExpand, backdropExpandDelay ->
                snapshot.copy(
                    focusedPosterBackdropExpandEnabled = backdropExpand,
                    focusedPosterBackdropExpandDelaySeconds = backdropExpandDelay
                )
            }
            val cardStyleSnapshotFlow = combine(
                focusedPosterSnapshotFlow,
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
                Log.d(TAG, "Layout snapshot changed: homeLayout=${snapshot.layout}")
                layoutOrderKeys.clear()
                layoutOrderKeys.addAll(snapshot.orderKeys)
                layoutDisabledKeys.clear()
                layoutDisabledKeys.addAll(snapshot.disabledKeys)
                heroCatalogKeys.clear()
                heroCatalogKeys.addAll(snapshot.heroKeys)
                heroSectionEnabled = snapshot.heroEnabled
                homeLayout = snapshot.layout
                Log.d(TAG, "homeLayout updated to: $homeLayout")
                catalogTypeSuffixEnabled = snapshot.catalogTypeSuffixEnabled
                hideUnreleasedContent = snapshot.hideUnreleasedContent
                followAddonsOrder = snapshot.followAddonsOrder
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
        val classicFocusGradientEnabled: Boolean = false,
        val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
        val useEpisodeThumbnailsInCw: Boolean = true,
        val blurContinueWatchingNextUp: Boolean = false,
        val posterCardWidthDp: Int = 126,
        val posterCardHeightDp: Int = 189,
        val posterCardCornerRadiusDp: Int = 12,
        val posterLabelsEnabled: Boolean = true,
        val catalogAddonNameEnabled: Boolean = false,
        val focusedPosterBackdropExpandEnabled: Boolean = false,
        val focusedPosterBackdropExpandDelaySeconds: Int = 3
    )

    private fun observeExtraAddons() {
        viewModelScope.launch {
            extraAddonRepository.getInstalledExtraAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    lastAddons = addons
                    val enabled = addons.filter { it.enabled }
                    if (enabled.isEmpty()) {
                        invalidateCatalogLoads()
                        synchronized(rows) { rows.clear() }
                        publishRows()
                        lastCatalogLoadSignature = null
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

    /** Stops in-flight loads writing into the rows map and drops hero enrichment. */
    private fun invalidateCatalogLoads() {
        catalogLoadGeneration.incrementAndGet()
        extraHeroEnrichmentJob?.cancel()
        extraHeroEnrichmentJob = null
        lastExtraHeroEnrichmentSignature = null
        lastExtraHeroEnrichedItems = emptyList()
    }

    private fun catalogKey(addon: Addon, catalog: CatalogDescriptor): String =
        "${addon.id}_${catalog.apiType}_${catalog.id}"

    private fun shouldShowCatalog(catalog: CatalogDescriptor): Boolean {
        val isSearchOnly = catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
        if (isSearchOnly) return false
        return !catalog.hasExplicitShowInHome || catalog.showInHome
    }

    private suspend fun loadAllCatalogs(addons: List<Addon>, forceReload: Boolean = false) {
        val signature = buildExtraCatalogLoadSignature(addons)
        // Same addon set already fully loaded — don't clear the rows just to
        // rebuild them (that flashed the whole tab on every addon emission).
        if (!forceReload &&
            signature == lastCatalogLoadSignature &&
            synchronized(rows) { rows.isNotEmpty() }
        ) {
            // Keep `error` untouched: if the stored rows are all itemless
            // (every load failed), clearing it would swap the Retry branch
            // for the generic empty state.
            _uiState.update {
                it.copy(isLoading = false, installedAddonsCount = addons.size)
            }
            return
        }

        val catalogsToLoad = addons.flatMap { addon ->
            addon.catalogs.filter(::shouldShowCatalog).map { addon to it }
        }
        if (catalogsToLoad.isEmpty()) {
            // Enabled addons but nothing showable (all search-only/hidden):
            // finish the load instead of leaving the spinner up forever.
            invalidateCatalogLoads()
            synchronized(rows) { rows.clear() }
            publishRows()
            lastCatalogLoadSignature = signature
            _uiState.update {
                it.copy(isLoading = false, error = null, installedAddonsCount = addons.size)
            }
            return
        }

        val generation = catalogLoadGeneration.incrementAndGet()
        extraHeroEnrichmentJob?.cancel()
        extraHeroEnrichmentJob = null
        lastExtraHeroEnrichmentSignature = null
        lastExtraHeroEnrichedItems = emptyList()

        // On reload keep the currently visible rows on screen while the new
        // ones stream in — same contract as the main/anime pipelines.
        val isReload = synchronized(rows) { rows.isNotEmpty() }
        if (isReload) {
            _uiState.update { it.copy(error = null, installedAddonsCount = addons.size) }
        } else {
            _uiState.update {
                it.copy(isLoading = true, error = null, installedAddonsCount = addons.size)
            }
            synchronized(rows) { rows.clear() }
        }

        val expectedKeys = catalogsToLoad.map { (addon, catalog) -> catalogKey(addon, catalog) }.toSet()
        synchronized(rows) {
            rows.keys.retainAll(expectedKeys)
            catalogsToLoad.forEach { (addon, catalog) ->
                rows.putIfAbsent(catalogKey(addon, catalog), emptyRow(addon, catalog))
            }
        }
        publishRows()

        // Structured concurrency: a new addon emission (collectLatest restart)
        // cancels all in-flight loads, and each load is concurrency-limited by
        // the semaphore instead of running one at a time.
        val failedCount = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        try {
            coroutineScope {
                catalogsToLoad.forEach { (addon, catalog) ->
                    launch {
                        catalogLoadSemaphore.withPermit {
                            val failure = loadCatalog(addon, catalog, generation)
                            if (failure != null) {
                                failedCount.incrementAndGet()
                                firstError.compareAndSet(null, failure)
                            }
                        }
                    }
                }
            }
            lastCatalogLoadSignature = signature
            val failures = failedCount.get()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = if (failures > 0 && failures == catalogsToLoad.size) {
                        firstError.get()
                    } else {
                        null
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Extra catalog bulk load failed", e)
            lastCatalogLoadSignature = null
            _uiState.update { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun buildExtraCatalogLoadSignature(addons: List<Addon>): String =
        addons.joinToString(separator = ",") { addon ->
            val catalogs = addon.catalogs.joinToString(separator = ";") { catalog ->
                listOf(
                    catalog.apiType,
                    catalog.id,
                    catalog.name,
                    catalog.showInHome.toString(),
                    catalog.hasExplicitShowInHome.toString(),
                    catalog.pageSize?.toString().orEmpty()
                ).joinToString("|")
            }
            listOf(
                addon.id,
                addon.baseUrl,
                addon.version,
                addon.displayName,
                catalogs
            ).joinToString("|")
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

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, generation: Int): String? {
        val key = catalogKey(addon, catalog)
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        Log.d(TAG, "Loading extra catalog addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id}")
        var sawTerminal = false
        var errorMessage: String? = null
        try {
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
                // A newer load generation owns the rows map — drop stale results.
                if (generation != catalogLoadGeneration.get()) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        sawTerminal = true
                        synchronized(rows) { rows[key] = result.data }
                        publishRows()
                    }
                    is NetworkResult.Error -> {
                        sawTerminal = true
                        errorMessage = result.message
                        synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false, items = emptyList()) }
                        publishRows()
                    }
                    NetworkResult.Loading -> { /* handled by row */ }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Extra catalog load failed addonId=${addon.id} catalogId=${catalog.id}", e)
            if (generation == catalogLoadGeneration.get()) {
                synchronized(rows) { rows[key] = emptyRow(addon, catalog).copy(isLoading = false, items = emptyList()) }
                publishRows()
            }
            return "${addon.displayName}: ${e.message ?: "load failed"}"
        }
        if (generation != catalogLoadGeneration.get()) return null
        if (!sawTerminal) return "${addon.displayName}: empty response"
        return errorMessage
    }

    fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val currentRow = synchronized(rows) { rows[key] }
        if (currentRow == null || currentRow.isLoading || !currentRow.hasMore) return

        synchronized(rows) { rows[key] = currentRow.copy(isLoading = true) }
        publishRows()

        viewModelScope.launch {
            try {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Extra catalog load-more failed catalogId=$catalogId", e)
                synchronized(rows) {
                    val latest = rows[key]
                    if (latest != null) {
                        rows[key] = latest.copy(isLoading = false)
                    }
                }
                publishRows()
            }
        }
    }

    fun ensureCatalogLoaded(catalogId: String, addonId: String, type: String) {
        val key = "${addonId}_${type}_${catalogId}"
        val existing = synchronized(rows) { rows[key] }
        // Empty rows are failed (or genuinely empty) catalogs — they must be
        // retried, otherwise an error on first load blocked See All forever.
        val firstId = existing?.items?.firstOrNull()?.id
        val hasRealContent = existing != null &&
            existing.items.isNotEmpty() &&
            firstId?.startsWith("__placeholder_") != true
        if (hasRealContent) return

        val addon = lastAddons.firstOrNull { it.id == addonId } ?: return
        val catalog = addon.catalogs.firstOrNull { it.apiType == type && it.id == catalogId } ?: return
        if (!shouldShowCatalog(catalog)) return

        val generation = catalogLoadGeneration.get()
        viewModelScope.launch {
            catalogLoadSemaphore.withPermit {
                val current = synchronized(rows) { rows[key] }
                val currentFirstId = current?.items?.firstOrNull()?.id
                val currentHasRealContent = current != null &&
                    current.items.isNotEmpty() &&
                    currentFirstId?.startsWith("__placeholder_") != true
                if (currentHasRealContent) return@withPermit
                loadCatalog(addon, catalog, generation)
            }
        }
    }

    private fun publishRows() {
        Log.d(TAG, "publishRows: homeLayout=$homeLayout, rows=${synchronized(rows) { rows.size }}")
        val snapshot = synchronized(rows) { rows.values.toList() }
        val ordered = orderRows(snapshot)
        val today = java.time.LocalDate.now()
        val released = if (hideUnreleasedContent) {
            ordered.map { it.filterReleasedItems(today) }
        } else {
            ordered
        }
        // See All must show the same rows the tab renders (ordered, disabled
        // excluded, unreleased filtered) — same contract as the regular home.
        if (_fullCatalogRows.value != released) {
            _fullCatalogRows.value = released
        }
        val filtered = released.filter { it.items.isNotEmpty() }
        val heroRow = computeHeroRow(visibleRows = filtered, allRows = snapshot, today = today)
        val heroItems = heroRow?.items.orEmpty()

        _uiState.update { state ->
            val updated = state.copy(
                rows = filtered,
                heroEnabled = heroSectionEnabled,
                heroItems = heroItems,
                heroAddonBaseUrl = heroRow?.addonBaseUrl,
                homeLayout = homeLayout,
                catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
                hideUnreleasedContent = hideUnreleasedContent,
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
                focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds
            )
            if (updated == state) state else updated
        }
        // Called AFTER the state update so the cached-enriched restore (same
        // signature) is not overwritten by the raw heroItems written above.
        enrichExtraHeroItemsIfNeeded(heroItems)
    }

    fun removeContinueWatching(item: ContinueWatchingItem) {
        when (item) {
            is ContinueWatchingItem.InProgress -> removeExtraContinueWatchingPipeline(
                contentId = item.progress.contentId,
                season = item.progress.season,
                episode = item.progress.episode,
                isNextUp = false
            )
            is ContinueWatchingItem.NextUp -> removeExtraContinueWatchingPipeline(
                contentId = item.info.contentId,
                season = item.info.seedSeason,
                episode = item.info.seedEpisode,
                isNextUp = true,
                contentType = item.info.contentType
            )
        }
    }

    private fun orderRows(all: List<CatalogRow>): List<CatalogRow> {
        val enabled = all.filterNot {
            layoutDisabledKeys.contains(homeCatalogKey(it.addonId, it.rawType, it.catalogId))
        }
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

    private fun computeHeroRow(
        visibleRows: List<CatalogRow>,
        allRows: List<CatalogRow>,
        today: java.time.LocalDate
    ): CatalogRow? {
        if (!heroSectionEnabled) return null
        fun isRealRow(row: CatalogRow): Boolean =
            row.items.isNotEmpty() &&
                row.items.firstOrNull()?.id?.startsWith("__placeholder_") != true
        if (heroCatalogKeys.isNotEmpty()) {
            // Selected hero catalogs keep driving the hero even when disabled
            // from the visible rows (main/anime parity) — lookup the full
            // snapshot first instead of silently jumping to another catalog.
            val visibleKeys = visibleRows
                .map { homeCatalogKey(it.addonId, it.rawType, it.catalogId) }
                .toSet()
            val heroOnlyRows = allRows.filter { row ->
                val key = homeCatalogKey(row.addonId, row.rawType, row.catalogId)
                key in heroCatalogKeys && key !in visibleKeys
            }.let { selected ->
                if (hideUnreleasedContent) selected.map { it.filterReleasedItems(today) } else selected
            }
            val candidates = visibleRows + heroOnlyRows
            for (key in heroCatalogKeys) {
                candidates.firstOrNull {
                    homeCatalogKey(it.addonId, it.rawType, it.catalogId) == key
                }?.takeIf(::isRealRow)?.let { return it }
            }
            // Selection matches nothing (catalog uninstalled) — fall through.
        }
        if (visibleRows.isEmpty()) return null
        return visibleRows.firstOrNull(::isRealRow)
    }

    fun onEvent(event: ExtraHomeEvent) {
        when (event) {
            is ExtraHomeEvent.OnLoadMoreCatalog ->
                loadMoreCatalogItems(event.catalogId, event.addonId, event.type)
            ExtraHomeEvent.OnRetry -> {
                viewModelScope.launch {
                    val addons = lastAddons.filter { it.enabled }
                    if (addons.isNotEmpty()) loadAllCatalogs(addons, forceReload = true)
                }
            }
        }
    }
}
