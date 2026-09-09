package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
internal fun ClassicHomeRoute(
    viewModel: Any,
    uiState: Any,
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
    val focusState = (viewModel as? { val focusState: StateFlow<HomeScreenFocusState> })?.focusState?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = (viewModel as? { val scrollToTopTrigger: StateFlow<Int> })?.scrollToTopTrigger?.collectAsStateWithLifecycle()
    val trailerPreviewUrls = (viewModel as? { val trailerPreviewUrls: Map<String, String> })?.trailerPreviewUrls ?: emptyMap()
    val trailerPreviewAudioUrls = (viewModel as? { val trailerPreviewAudioUrls: Map<String, String> })?.trailerPreviewAudioUrls ?: emptyMap()
    ClassicHomeContent(
        uiState = uiState as HomeUiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        trailerPreviewUrls = trailerPreviewUrls,
        trailerPreviewAudioUrls = trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            (viewModel as? { fun onEvent(event: HomeEvent) })?.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
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
    viewModel: Any,
    uiState: Any,
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
    val gridFocusState = (viewModel as? { val gridFocusState: StateFlow<HomeScreenFocusState> })?.gridFocusState?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = (viewModel as? { val scrollToTopTrigger: StateFlow<Int> })?.scrollToTopTrigger?.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState as HomeUiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = remember(viewModel) {
            { contentId, season, episode, isNextUp ->
                (viewModel as? { fun onEvent(event: HomeEvent) })?.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = remember(viewModel) {
            { item ->
                (viewModel as? { fun onItemFocus(item: MetaPreview) })?.onItemFocus(item)
            }
        },
        onSaveGridFocusState = remember(viewModel) {
            { vi, vo, key ->
                (viewModel as? { fun saveGridFocusState(vi: Int, vo: Int, focusedItemKey: String) })?.saveGridFocusState(vi, vo, focusedItemKey = key)
            }
        }
    )
}

@Composable
internal fun ModernHomeRoute(
    viewModel: Any,
    uiState: Any,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState = (viewModel as? { val focusState: StateFlow<HomeScreenFocusState> })?.focusState?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = (viewModel as? { val scrollToTopTrigger: StateFlow<Int> })?.scrollToTopTrigger?.collectAsStateWithLifecycle()
    val enrichingItemId = (viewModel as? { val enrichingItemId: StateFlow<String?> })?.enrichingItemId?.collectAsStateWithLifecycle()
    val lastEnrichedPreview = (viewModel as? { val lastEnrichedPreview: StateFlow<MetaPreview?> })?.lastEnrichedPreview?.collectAsStateWithLifecycle()
    val enrichedPreviews = (viewModel as? { val enrichedPreviews: StateFlow<Map<String, MetaPreview>> })?.enrichedPreviews?.collectAsStateWithLifecycle()
    val failedEnrichmentIds = (viewModel as? { val failedEnrichmentIds: StateFlow<Set<String>> })?.failedEnrichmentIds?.collectAsStateWithLifecycle()
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
        uiState = uiState as HomeUiState,
        focusState = focusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        enrichingItemId = enrichingItemId?.value,
        lastEnrichedPreview = lastEnrichedPreview?.value,
        enrichedPreviews = enrichedPreviews?.value,
        failedEnrichmentIds = failedEnrichmentIds?.value,
        trailerPreviewUrls = (viewModel as? { val trailerPreviewUrls: Map<String, String> })?.trailerPreviewUrls ?: emptyMap(),
        trailerPreviewAudioUrls = (viewModel as? { val trailerPreviewAudioUrls: Map<String, String> })?.trailerPreviewAudioUrls ?: emptyMap(),
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
            { item -> (viewModel as? { fun onItemFocus(item: MetaPreview) })?.onItemFocus(item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> (viewModel as? { fun requestLazyCatalogLoad(catalogKey: String) })?.requestLazyCatalogLoad(catalogKey) }
        }
    )
}