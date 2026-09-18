package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card as TvCard
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarSection
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CARD_WIDTH = 120.dp
private val CARD_HEIGHT = 180.dp
private val WIDE_CARD_WIDTH = 260.dp
private val WIDE_CARD_HEIGHT = 146.dp
private val SECTION_PADDING_HORIZONTAL = 52.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit,
    viewModel: CalendarHomeViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
    ) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.calendar_error),
                            style = MaterialTheme.typography.headlineSmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TvCard(
                            onClick = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
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
                    }
                }
            }
            uiState.sections.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.calendar_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.calendar_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TvCard(
                            onClick = { viewModel.onEvent(CalendarHomeEvent.OnRetry) },
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
                    }
                }
            }
            else -> {
                val firstSection = uiState.sections.firstOrNull()
                val restSections = uiState.sections.drop(1)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    if (firstSection != null) {
                        item(key = "hero_header") {
                            CalendarHeroSection(
                                section = firstSection,
                                onNavigateToDetail = onNavigateToDetail
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

@Composable
private fun CalendarHeroSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit
) {
    val firstItem = section.items.firstOrNull() ?: return
    var focusedIndex by remember { mutableIntStateOf(0) }
    val focusedItem = remember(focusedIndex, section.items) {
        section.items.getOrNull(focusedIndex) ?: firstItem
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(520.dp)
            .clipToBounds()
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(focusedItem.meta.backdropUrl ?: focusedItem.meta.poster)
                .crossfade(true)
                .build(),
            contentDescription = focusedItem.meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Transparent,
                            NuvioTheme.colors.Background.copy(alpha = 0.5f),
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
                .padding(start = SECTION_PADDING_HORIZONTAL, end = 48.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.calendar_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(12.dp))
                SectionBadge(count = section.items.size)
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = section.label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val focusedGenres = remember(focusedItem) {
                focusedItem.meta.genres.take(3).joinToString(" \u00B7 ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            val focusedTypeLabel = remember(focusedItem) {
                when (focusedItem.meta.rawType.lowercase()) {
                    "movie" -> "Film"
                    "tv" -> "Serie TV"
                    else -> focusedItem.meta.rawType
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = focusedTypeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                focusedItem.meta.imdbRating?.let { rating ->
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "\u2605 ${String.format(Locale.US, "%.1f", rating)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = NuvioTheme.colors.Secondary
                    )
                }
                if (focusedGenres.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = focusedGenres,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }

            focusedItem.meta.description?.let { desc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                contentPadding = PaddingValues(end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(
                    items = section.items,
                    key = { _, it -> it.meta.id }
                ) { index, calendarItem ->
                    CalendarWideCard(
                        meta = calendarItem.meta,
                        releaseDate = calendarItem.releaseDate,
                        onClick = {
                            onNavigateToDetail(
                                calendarItem.meta.id,
                                calendarItem.meta.apiType,
                                calendarItem.meta.sourceAddonBaseUrl ?: ""
                            )
                        },
                        onFocusChange = { focused ->
                            if (focused) focusedIndex = index
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SECTION_PADDING_HORIZONTAL),
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

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = SECTION_PADDING_HORIZONTAL),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                items = section.items,
                key = { it.meta.id }
            ) { calendarItem ->
                CalendarPortraitCard(
                    meta = calendarItem.meta,
                    releaseDate = calendarItem.releaseDate,
                    onClick = {
                        onNavigateToDetail(
                            calendarItem.meta.id,
                            calendarItem.meta.apiType,
                            calendarItem.meta.sourceAddonBaseUrl ?: ""
                        )
                    }
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
    onClick: () -> Unit,
    onFocusChange: (Boolean) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }
    val dateLabel = remember(releaseDate) {
        releaseDate?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ITALIAN)) ?: ""
    }
    val typeLabel = remember(meta.rawType) {
        when (meta.rawType.lowercase()) {
            "movie" -> "Film"
            "tv" -> "Serie TV"
            else -> meta.rawType
        }
    }
    val cardShape = RoundedCornerShape(12.dp)

    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(WIDE_CARD_WIDTH)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChange(it.isFocused)
            },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
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
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(meta.backdropUrl ?: meta.poster)
                        .crossfade(true)
                        .build(),
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (dateLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
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

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = meta.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                color = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
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
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarPortraitCard(
    meta: MetaPreview,
    releaseDate: java.time.LocalDate?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val dateLabel = remember(releaseDate) {
        releaseDate?.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ITALIAN)) ?: ""
    }
    val genresText = remember(meta.genres) {
        meta.genres.take(2).joinToString(" \u00B7 ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
    val cardShape = RoundedCornerShape(10.dp)

    TvCard(
        onClick = onClick,
        modifier = Modifier
            .width(CARD_WIDTH)
            .onFocusChanged { isFocused = it.isFocused },
        shape = CardDefaults.shape(shape = cardShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioTheme.colors.FocusRing),
                shape = cardShape
            )
        )
    ) {
        Column(
            modifier = Modifier.width(CARD_WIDTH)
        ) {
            Box(
                modifier = Modifier
                    .width(CARD_WIDTH)
                    .height(CARD_HEIGHT)
                    .clip(cardShape)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(meta.poster)
                        .crossfade(true)
                        .build(),
                    contentDescription = meta.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (dateLabel.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = dateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = meta.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                color = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (genresText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = genresText,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
