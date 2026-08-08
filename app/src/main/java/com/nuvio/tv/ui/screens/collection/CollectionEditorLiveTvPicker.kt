package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.LiveTvCollectionSource
import com.nuvio.tv.domain.model.LiveTvPlaylist
import com.nuvio.tv.ui.components.LoadingIndicator

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvSourcePickerContent(
    playlists: List<LiveTvPlaylist>,
    isLoading: Boolean,
    alreadyAdded: List<CollectionSource>,
    onToggle: (LiveTvPlaylist) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = NuvioTheme.spacing.xxxl, start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.collections_editor_add_livetv_source),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            NuvioButton(onClick = onBack) { Text(stringResource(R.string.collections_editor_done)) }
        }

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            playlists.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.collections_editor_no_livetv_playlists),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xxxl),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    itemsIndexed(
                        items = playlists,
                        key = { index, playlist -> "${playlist.id}_$index" }
                    ) { _, playlist ->
                        val isAdded = alreadyAdded.any {
                            it is LiveTvCollectionSource && it.playlistId == playlist.id
                        }
                        Card(
                            onClick = { onToggle(playlist) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.colors(
                                containerColor = if (isAdded) NuvioTheme.colors.Secondary.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                border = if (isAdded) Border(
                                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Secondary.copy(alpha = 0.5f)),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                ) else Border.None,
                                focusedBorder = Border(
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                )
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                            scale = CardDefaults.scale(focusedScale = 1.01f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(NuvioTheme.spacing.lg),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = NuvioTheme.colors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sourceTypeLabel = if (playlist.isXtream) {
                                        stringResource(R.string.live_tv_source_xtream)
                                    } else {
                                        stringResource(R.string.live_tv_source_m3u)
                                    }
                                    Text(
                                        text = "$sourceTypeLabel • ${playlist.sourceUrl}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioTheme.colors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isAdded) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.collection_editor_remove_cd),
                                        tint = NuvioTheme.colors.TextSecondary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.cd_add),
                                        tint = NuvioTheme.colors.TextTertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
