package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private val HERO_HEIGHT = 380.dp
private val WIDE_CARD_WIDTH = 300.dp
private val WIDE_CARD_HEIGHT = 170.dp
private val SECTION_PADDING_HORIZONTAL = 48.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    viewModel: CalendarHomeViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val firstCardFocusRequester = remember { FocusRequester() }

    // Error/empty must be reachable: only show the spinner while loading with
    // nothing to display yet (previous logic hid both states forever).
    val showContent = uiState.sections.isNotEmpty()
    val showSpinner = uiState.isLoading && !showContent
    val showError = !showSpinner && !showContent && uiState.error != null
    val showEmpty = !showSpinner && !showError && !showContent && uiState.hasLoaded

    LaunchedEffect(showContent, uiState.sections.size) {
        if (showContent && uiState.sections.all { true }) {
            delay(50)
            runCatching { firstCardFocusRequester.requestFocusAfterFrames(2) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when {
            showSpinner -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            showError -> {
                CalendarMessageState(
                    title = stringResource(R.string.calendar_error),
                    subtitle = uiState.error ?: "",
                    onRetry = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
                    focusRequester = firstCardFocusRequester
                )
            }
            showEmpty -> {
                CalendarMessageState(
                    title = stringResource(R.string.calendar_empty_title),
                    subtitle = stringResource(R.string.calendar_empty_subtitle),
                    onRetry = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
                    focusRequester = firstCardFocusRequester
                )
            }
            else -> {
                val firstSection = uiState.sections.firstOrNull()
                val restSections = uiState.sections.drop(1)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 160.dp)
                ) {
                    if (firstSection != null) {
                        item(key = "hero_header") {
                            CalendarHeroSection(
                                section = firstSection,
                                onNavigateToDetail = onNavigateToDetail,
                                initialFocusRequester = firstCardFocusRequester
                            )
                        }
                    }

                    itemsIndexed(
                        items = restSections,
                        key = { _, section -> section.label }
                    ) { _, section ->
                        CalendarSection(
                            section = section,
                            onNavigateToDetail = onNavigateToDetail
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarMessageState(
    title: String,
    subtitle: String,
    onRetry: () -> Unit,
    focusRequester: FocusRequester
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            TvCard(
                onClick = onRetry,
                modifier = Modifier.focusRequester(focusRequester),
                shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.Secondary
                ),
                border = CardDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            LaunchedEffect(Unit) {
                delay(50)
                runCatching { focusRequester.requestFocusAfterFrames(2) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CalendarHeroSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    initialFocusRequester: FocusRequester
) {
    val firstItem = section.items.firstOrNull() ?: return
    var focusedIndex by rememberSaveable(section.label) { mutableIntStateOf(0) }
    var userInteracting by remember { mutableStateOf(false) }
    // Use the full row for rotation so backdrop index always matches the
    // focused card (previous take(10) desynced with >10 items).
    val heroItems = section.items
    val focusedItem = heroItems.getOrNull(focusedIndex) ?: firstItem
    val heroRowState = rememberLazyListState()
    val firstWideCardRequester = remember { FocusRequester() }

    // Keep Detail's hero backdrop in sync with the calendar hero so back/nav
    // does not inherit a stale Home backdrop.
    LaunchedEffect(focusedItem.meta.backdropUrl) {
        HeroBackdropState.update(focusedItem.meta.backdropUrl)
    }

    LaunchedEffect(heroItems.size, userInteracting) {
        if (heroItems.size <= 1) return@LaunchedEffect
        while (true) {
            delay(5000L)
            if (!userInteracting) {
                focusedIndex = (focusedIndex + 1) % heroItems.size
                val next = focusedIndex
                if (next < heroItems.size) {
                    runCatching { heroRowState.scrollToItem(next) }
                }
            }
        }
    }

    LaunchedEffect(userInteracting) {
        if (userInteracting) {
            delay(8000L)
            userInteracting = false
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HERO_HEIGHT)
        ) {
            if (focusedItem.meta.backdropUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(focusedItem.meta.backdropUrl)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = focusedItem.meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    NuvioTheme.colors.Secondary.copy(alpha = 0.3f),
                                    NuvioTheme.colors.Background
                                )
                            )
                        )
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f),
                                Color.Transparent,
                                NuvioTheme.colors.Background.copy(alpha = 0.6f),
                                NuvioTheme.colors.Background
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NuvioTheme.colors.Background.copy(alpha = 0.7f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = SECTION_PADDING_HORIZONTAL, end = 48.dp, bottom = 20.dp)
                    .fillMaxWidth(0.55f)
            ) {
                focusedItem.meta.logo?.let { logoUrl ->
                    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
                    if (!logoLoadFailed) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(logoUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = focusedItem.meta.name,
                            onError = { logoLoadFailed = true },
                            modifier = Modifier
                                .height(80.dp)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = focusedItem.meta.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } ?: run {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = focusedItem.meta.name,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        SectionBadge(count = section.items.size)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val focusedTypeLabel = remember(focusedItem) {
                    when (focusedItem.meta.rawType.lowercase()) {
                        "movie" -> "Film"
                        "tv", "series" -> "Serie TV"
                        else -> focusedItem.meta.rawType
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    focusedItem.meta.imdbRating?.let { rating ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                        ) {
                            Text(
                                text = "\u2605",
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.colors.Secondary
                            )
                            val ratingText = remember(rating) { String.format(Locale.US, "%.1f", rating) }
                            Text(
                                text = ratingText,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }

                    val releaseYear = remember(focusedItem.meta.releaseInfo) {
                        focusedItem.meta.releaseInfo?.let { releaseInfo ->
                            releaseInfo.split("-").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }
                    releaseYear?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    Text(
                        text = focusedTypeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }

                if (focusedItem.meta.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                    ) {
                        focusedItem.meta.genres.take(3).forEach { genre ->
                            Text(
                                text = genre,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
                            )
                        }
                    }
                }

                focusedItem.meta.description?.let { desc ->
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        LazyRow(
            state = heroRowState,
            contentPadding = PaddingValues(start = SECTION_PADDING_HORIZONTAL, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .padding(top = 8.dp)
                .focusGroup()
                .focusRestorer { firstWideCardRequester }
        ) {
            itemsIndexed(
                items = heroItems,
                key = { _, it -> it.meta.id }
            ) { index, calendarItem ->
                CalendarWideCard(
                    meta = calendarItem.meta,
                    releaseDate = calendarItem.releaseDate,
                    notInCatalog = calendarItem.notInCatalog,
                    onClick = {
                        // Always open Detail with a blank source addon so the
                        // detail page races Home + Anime + Extra meta pools.
                        onNavigateToDetail(
                            calendarItem.meta.id,
                            calendarItem.meta.apiType,
                            ""
                        )
                    },
                    onFocusChange = { focused ->
                        if (focused) {
                            focusedIndex = index
                            userInteracting = true
                        }
                    },
                    focusRequester = if (index == 0) firstWideCardRequester else null,
                    initialFocusRequester = if (index == 0) initialFocusRequester else null
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CalendarSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit
) {
    val rowState = rememberLazyListState()
    val firstPosterRequester = remember(section.label) { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NuvioTheme.colors.Background)
                    .padding(horizontal = SECTION_PADDING_HORIZONTAL, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary
                )
                SectionBadge(count = section.items.size)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = SECTION_PADDING_HORIZONTAL),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .focusGroup()
                .focusRestorer { firstPosterRequester }
        ) {
            itemsIndexed(
                items = section.items,
                key = { _, it -> it.meta.id }
            ) { index, calendarItem ->
                CalendarPortraitCard(
                    meta = calendarItem.meta,
                    releaseDate = calendarItem.releaseDate,
                    notInCatalog = calendarItem.notInCatalog,
                    onClick = {
                        onNavigateToDetail(
                            calendarItem.meta.id,
                            calendarItem.meta.apiType,
                            ""
                        )
                    },
                    focusRequester = if (index == 0) firstPosterRequester else null
                )
            }
        }
    }
}

@Composable
private fun SectionBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NuvioTheme.colors.Secondary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Secondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarWideCard(
    meta: MetaPreview,
    releaseDate: java.time.LocalDate?,
    notInCatalog: Boolean = false,
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null,
    initialFocusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val dateLabel = remember(releaseDate) {
        releaseDate?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())) ?: ""
    }
    val typeLabel = remember(meta.rawType) {
        when (meta.rawType.lowercase()) {
            "movie" -> "Film"
            "tv", "series" -> "Serie TV"
            else -> meta.rawType
        }
    }
    val cardShape = RoundedCornerShape(12.dp)

    // Share the same FocusRequester instance for restorer + initial grab.
    val requester = focusRequester ?: remember { FocusRequester() }

    LaunchedEffect(initialFocusRequester, requester) {
        if (initialFocusRequester != null) {
            delay(50)
            runCatching { requester.requestFocusAfterFrames(2) }
        }
    }

    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(WIDE_CARD_WIDTH)
            .focusRequester(requester)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChange(it.isFocused)
                if (it.isFocused) {
                    HeroBackdropState.update(meta.backdropUrl)
                }
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = cardShape
            )
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WIDE_CARD_HEIGHT)
                    .clip(cardShape)
            ) {
                if (meta.backdropUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(meta.backdropUrl)
                            .crossfade(true)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = meta.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        NuvioTheme.colors.Secondary.copy(alpha = 0.4f),
                                        NuvioTheme.colors.BackgroundCard
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = meta.name.take(1).uppercase(Locale.getDefault()),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.3f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f)
                                )
                            )
                        )
                )

                if (dateLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NuvioTheme.colors.BackgroundCard.copy(alpha = 0.85f),
                                NuvioTheme.colors.BackgroundCard
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    color = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                    meta.imdbRating?.let { rating ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "\u2605 ${String.format(Locale.US, "%.1f", rating)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = NuvioTheme.colors.Secondary
                        )
                    }
                }

                if (notInCatalog) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.calendar_not_in_catalog),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarPortraitCard(
    meta: MetaPreview,
    releaseDate: java.time.LocalDate?,
    notInCatalog: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val dateLabel = remember(releaseDate) {
        releaseDate?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())) ?: ""
    }
    val cardShape = RoundedCornerShape(12.dp)
    val cardWidth = 140.dp
    val cardHeight = 210.dp

    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(cardWidth)
            .height(cardHeight)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) {
                    HeroBackdropState.update(meta.backdropUrl)
                }
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = cardShape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(cardShape)
        ) {
            if (meta.poster != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(meta.poster)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NuvioTheme.colors.BackgroundCard),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = meta.name.take(1).uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.3f)
                    )
                }
            }

            if (dateLabel.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            if (notInCatalog) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.calendar_not_in_catalog),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
