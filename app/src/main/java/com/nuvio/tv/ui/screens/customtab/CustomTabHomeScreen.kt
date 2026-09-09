package com.nuvio.tv.ui.screens.customtab

import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalTvMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.screens.home.ClassicHomeRoute
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.GridHomeRoute
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.ModernHomeRoute
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val HOME_STABLE_GATE_TIMEOUT_MS = 5_000L

@Composable
fun CustomTabHomeScreen(
    viewModel: CustomTabHomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (com.nuvio.tv.ui.screens.home.ContinueWatchingItem) -> Unit = {},
    onContinueWatchingStartFromBeginning: (com.nuvio.tv.ui.screens.home.ContinueWatchingItem) -> Unit = {},
    onContinueWatchingPlayManually: (com.nuvio.tv.ui.screens.home.ContinueWatchingItem) -> Unit = {},
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onOpenAddons: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasCatalogContent = uiState.rows.any { it.items.isNotEmpty() }
    val hasHeroContent = uiState.heroItems.isNotEmpty()
    val modernPresentationReady =
        uiState.homeLayout != HomeLayout.MODERN ||
            uiState.heroItems.isNotEmpty() ||
            (uiState.heroEnabled && hasHeroContent && !hasCatalogContent)
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var hasShownInitialHomeContent by rememberSaveable { mutableStateOf(false) }
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.homeLayout) {
        if (uiState.homeLayout != HomeLayout.MODERN) {
            HeroBackdropState.update(null)
        }
    }

    val movieWatchedStatus = emptyMap<String, Boolean>()
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview -> latestMovieWatchedStatus.value["${item.id}|${item.apiType}"] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember {
        { _, _ -> }
    }

    val onNavigateToDetailStable = remember(onNavigateToDetail) { onNavigateToDetail }
    val onContinueWatchingClickStable = remember(onContinueWatchingClick) { onContinueWatchingClick }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToCatalogSeeAllStable = remember(onNavigateToCatalogSeeAll) { onNavigateToCatalogSeeAll }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }
    val onRemoveContinueWatchingStable = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }

    LaunchedEffect(
        uiState.isLoading,
        hasCatalogContent,
        hasHeroContent
    ) {
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        if (!homeStableGateReleased &&
            catalogLoadingStarted &&
            !uiState.isLoading &&
            modernPresentationReady &&
            (hasCatalogContent || uiState.installedAddonsCount == 0)
        ) {
            homeStableGateReleased = true
        }
    }

    LaunchedEffect(Unit) {
        delay(HOME_STABLE_GATE_TIMEOUT_MS)
        if (!homeStableGateReleased) {
            homeStableGateReleased = true
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardHeightDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        com.nuvio.tv.ui.components.PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = com.nuvio.tv.ui.components.PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = com.nuvio.tv.ui.components.PosterCardDefaults.Style.focusedScale
        )
    }

    val noAddonsError = stringResource(R.string.home_error_no_addons)
    val noCatalogAddonsError = stringResource(R.string.home_error_no_catalog_addons)
    val hasAnyContent = uiState.rows.isNotEmpty() ||
        uiState.continueWatchingItems.isNotEmpty() ||
        uiState.heroItems.isNotEmpty()
    val showStartupLoader = when {
        uiState.isLoading && !hasAnyContent -> true
        uiState.error == noAddonsError && uiState.rows.isEmpty() -> !homeStableGateReleased
        uiState.error == noCatalogAddonsError && uiState.rows.isEmpty() && !hasHeroContent -> !homeStableGateReleased
        uiState.error != null && uiState.rows.isEmpty() -> false
        !uiState.isLoading && !hasAnyContent -> !homeStableGateReleased
        else -> !homeStableGateReleased || !modernPresentationReady || !showHomeContentWithAnimation
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            uiState.isLoading && !hasAnyContent -> {
                Unit
            }

            uiState.error == noAddonsError && uiState.rows.isEmpty() -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        EmptyScreenState(
                            title = stringResource(R.string.home_error_no_addons),
                            subtitle = stringResource(R.string.home_empty_addons_subtitle),
                            icon = Icons.Default.VideoLibrary
                        )
                        androidx.tv.material3.Button(onClick = onOpenAddons) {
                            androidx.tv.material3.Text(stringResource(R.string.home_empty_open_addons))
                        }
                    }
                }
            }

            uiState.error == noCatalogAddonsError && uiState.rows.isEmpty() && !hasHeroContent -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        EmptyScreenState(
                            title = stringResource(R.string.home_error_no_catalog_addons),
                            subtitle = stringResource(R.string.home_empty_catalogs_subtitle),
                            icon = Icons.Default.VideoLibrary
                        )
                        androidx.tv.material3.Button(onClick = onOpenAddons) {
                            androidx.tv.material3.Text(stringResource(R.string.home_empty_open_addons))
                        }
                    }
                }
            }

            uiState.error != null && uiState.rows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            !uiState.isLoading && !hasAnyContent -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    EmptyScreenState(
                        title = stringResource(R.string.web_no_catalogs),
                        subtitle = stringResource(R.string.home_empty_catalogs_subtitle),
                        icon = Icons.Default.Home
                    )
                }
            }

            else -> {
                if (!homeStableGateReleased) {
                    Unit
                } else if (!modernPresentationReady) {
                    Unit
                } else {
                    LaunchedEffect(Unit) {
                        if (!showHomeContentWithAnimation) {
                            kotlinx.coroutines.yield()
                            showHomeContentWithAnimation = true
                        }
                    }
                    LaunchedEffect(showHomeContentWithAnimation) {
                        if (showHomeContentWithAnimation) {
                            hasShownInitialHomeContent = true
                        }
                    }
                    if (!showHomeContentWithAnimation) {
                        Unit
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = if (hasShownInitialHomeContent) {
                            androidx.compose.animation.EnterTransition.None
                        } else {
                            fadeIn(animationSpec = tween(320)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(320)
                                )
                        }
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = false,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = false,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = false,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )
                        }
                    }
                }
            }
        }

        if (showStartupLoader) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }
    }
}