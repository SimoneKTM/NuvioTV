package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val CARD_WIDTH = 130.dp
private val CARD_HEIGHT = 195.dp
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
                    }
                }
            }
            else -> {
                val firstSection = uiState.sections.firstOrNull()
                val restSections = uiState.sections.drop(1)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(firstItem.meta.backdropUrl ?: firstItem.meta.poster)
                .crossfade(true)
                .build(),
            contentDescription = firstItem.meta.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            NuvioTheme.colors.Background.copy(alpha = 0.6f),
                            NuvioTheme.colors.Background
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

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = section.label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyRow(
                contentPadding = PaddingValues(end = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = section.items,
                    key = { "${it.meta.id}_${it.meta.type}" }
                ) { calendarItem ->
                    CalendarWideCard(
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
}

@Composable
private fun CalendarSection(
    section: CalendarSection,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = section.items,
                key = { "${it.meta.id}_${it.meta.type}" }
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

@Composable
private fun CalendarWideCard(
    meta: MetaPreview,
    releaseDate: java.time.LocalDate?,
    onClick: () -> Unit
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

    Column(
        modifier = Modifier
            .width(WIDE_CARD_WIDTH)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WIDE_CARD_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
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

            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }

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

    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .width(CARD_WIDTH)
                .height(CARD_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
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

            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }

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
