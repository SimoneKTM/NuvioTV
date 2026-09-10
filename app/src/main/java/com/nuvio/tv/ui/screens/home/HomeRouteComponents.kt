package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.flow.StateFlow

// Define a common interface for home route view models to avoid reflection
interface HomeRouteViewModelInternal {
    val focusState: StateFlow<HomeScreenFocusState>
    val gridFocusState: StateFlow<HomeScreenFocusState>
    val scrollToTopTrigger: StateFlow<Int>
    val trailerPreviewUrls: Map<String, String>
    val trailerPreviewAudioUrls: Map<String, String>
    val enrichingItemId: StateFlow<String?>
    val lastEnrichedPreview: StateFlow<MetaPreview?>
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>>
    val failedEnrichmentIds: StateFlow<Set<String>>
    
    fun onEvent(event: HomeEvent)
    fun requestTrailerPreview(item: MetaPreview)
    fun requestTrailerPreview(itemId: String, title: String, releaseInfo: String?, apiType: String)
    fun onItemFocus(item: MetaPreview)
    fun saveFocusState(
        vi: Int, vo: Int, rk: String?, ikm: Map<String, String>,
        m: Map<String, Int>, ri: Int, ii: Int
    )
    fun saveGridFocusState(
        verticalScrollIndex: Int,
        verticalScrollOffset: Int,
        focusedRowIndex: Int = 0,
        focusedItemIndex: Int = 0,
        focusedItemKey: String? = null
    )
    fun requestLazyCatalogLoad(catalogKey: String)
    fun preloadAdjacentItem(item: MetaPreview)
}

@Composable
internal fun ClassicHomeRoute(
    viewModel: HomeRouteViewModelInternal,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState = viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState.value,
        scrollToTopTrigger = scrollToTopTrigger.value,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = { item ->
            viewModel.requestTrailerPreview(item)
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ri, ii)
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

@Composable
internal fun GridHomeRoute(
    viewModel: HomeRouteViewModelInternal,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val gridFocusState = viewModel.gridFocusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState.value,
        scrollToTopTrigger = scrollToTopTrigger.value,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = remember(viewModel) {
            { contentId, season, episode, isNextUp ->
                viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = remember(viewModel) {
            { item ->
                viewModel.onItemFocus(item)
            }
        },
        onSaveGridFocusState = remember(viewModel) {
            { vi, vo, key ->
                viewModel.saveGridFocusState(
                    verticalScrollIndex = vi,
                    verticalScrollOffset = vo,
                    focusedItemKey = key
                )
            }
        }
    )
}

@Composable
internal fun ModernHomeRoute(
    viewModel: HomeRouteViewModelInternal,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState = viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val enrichingItemId = viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val lastEnrichedPreview = viewModel.lastEnrichedPreview.collectAsStateWithLifecycle()
    val enrichedPreviews = viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds = viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ri: Int, ii: Int ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ri, ii)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    ModernHomeContent(
        uiState = uiState,
        focusState = focusState.value,
        scrollToTopTrigger = scrollToTopTrigger.value,
        enrichingItemId = enrichingItemId.value,
        lastEnrichedPreview = lastEnrichedPreview.value,
        enrichedPreviews = enrichedPreviews.value,
        failedEnrichmentIds = failedEnrichmentIds.value,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onItemFocus = remember(viewModel) {
            { item -> viewModel.onItemFocus(item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

// Adapter to use HomeViewModel with the internal interface
@Composable
internal fun <T : HomeRouteViewModelInternal> ClassicHomeRoute(
    viewModel: T,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) = ClassicHomeRoute(
    viewModel = viewModel,
    uiState = uiState,
    posterCardStyle = posterCardStyle,
    onNavigateToDetail = onNavigateToDetail,
    onContinueWatchingClick = onContinueWatchingClick,
    onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
    onContinueWatchingPlayManually = onContinueWatchingPlayManually,
    showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
    onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
    onNavigateToFolderDetail = onNavigateToFolderDetail,
    isCatalogItemWatched = isCatalogItemWatched,
    onCatalogItemLongPress = onCatalogItemLongPress
)

@Composable
internal fun <T : HomeRouteViewModelInternal> GridHomeRoute(
    viewModel: T,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) = GridHomeRoute(
    viewModel = viewModel,
    uiState = uiState,
    posterCardStyle = posterCardStyle,
    onNavigateToDetail = onNavigateToDetail,
    onContinueWatchingClick = onContinueWatchingClick,
    onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
    onContinueWatchingPlayManually = onContinueWatchingPlayManually,
    showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
    onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
    onNavigateToFolderDetail = onNavigateToFolderDetail,
    isCatalogItemWatched = isCatalogItemWatched,
    onCatalogItemLongPress = onCatalogItemLongPress
)

@Composable
internal fun <T : HomeRouteViewModelInternal> ModernHomeRoute(
    viewModel: T,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) = ModernHomeRoute(
    viewModel = viewModel,
    uiState = uiState,
    onNavigateToDetail = onNavigateToDetail,
    onContinueWatchingClick = onContinueWatchingClick,
    onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
    onContinueWatchingPlayManually = onContinueWatchingPlayManually,
    showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
    onNavigateToFolderDetail = onNavigateToFolderDetail,
    isCatalogItemWatched = isCatalogItemWatched,
    onCatalogItemLongPress = onCatalogItemLongPress
)