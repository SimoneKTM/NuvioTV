@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvPlaylist
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle

@Composable
fun LiveTvScreen(
    viewModel: LiveTvViewModel = hiltViewModel(),
    onPlayChannel: (String, String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState.playlists.isEmpty() && !uiState.isAdding && uiState.addError == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                EmptyScreenState(
                    title = stringResource(R.string.live_tv_empty_title),
                    subtitle = stringResource(R.string.live_tv_empty_subtitle),
                    icon = Icons.Default.LiveTv
                )
                LiveTvAddButton(
                    enabled = !uiState.isAdding,
                    onClick = { showAddDialog = true }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .dpadRepeatThrottle(),
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
            ) {
                item(key = "live_tv_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = NuvioTheme.spacing.xxxl,
                                end = NuvioTheme.spacing.xxxl,
                                top = NuvioTheme.spacing.xl,
                                bottom = NuvioTheme.spacing.lg
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                            Text(
                                text = stringResource(R.string.nav_live_tv),
                                style = MaterialTheme.typography.headlineLarge,
                                color = NuvioTheme.colors.TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.live_tv_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        LiveTvAddButton(
                            enabled = !uiState.isAdding,
                            onClick = { showAddDialog = true }
                        )
                    }
                }

                item(key = "live_tv_playlists_title") {
                    LiveTvSectionLabel(text = stringResource(R.string.live_tv_playlists_title))
                }

                items(uiState.playlists, key = { it.playlist.id }) { playlistState ->
                    LiveTvPlaylistRow(
                        playlist = playlistState.playlist,
                        channelsCount = playlistState.channels.size,
                        isLoading = playlistState.isLoading,
                        errorMessage = playlistState.errorMessage,
                        onRefresh = { viewModel.refreshPlaylist(playlistState.playlist) },
                        onRemove = { viewModel.removePlaylist(playlistState.playlist) }
                    )
                }

                val channels = uiState.channels
                if (channels.isNotEmpty()) {
                    item(key = "live_tv_channels_title") {
                        LiveTvSectionLabel(text = stringResource(R.string.live_tv_channels_title))
                    }
                    items(channels, key = { it.id }) { channel ->
                        LiveTvChannelRow(
                            channel = channel,
                            onPlay = { onPlayChannel(channel.name, channel.streamUrl) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        LiveTvAddPlaylistDialog(
            isBusy = uiState.isAdding,
            errorMessage = uiState.addError,
            onAdd = { url ->
                viewModel.addPlaylist(url) {
                    showAddDialog = false
                }
            },
            onDismiss = {
                viewModel.clearAddError()
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun LiveTvAddButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.Primary)
    ) {
        Text(stringResource(R.string.live_tv_add_playlist))
    }
}

@Composable
private fun LiveTvSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(
            start = NuvioTheme.spacing.xxxl,
            end = NuvioTheme.spacing.xxxl,
            top = NuvioTheme.spacing.lg,
            bottom = NuvioTheme.spacing.sm
        )
    )
}

@Composable
private fun LiveTvPlaylistRow(
    playlist: LiveTvPlaylist,
    channelsCount: Int,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Card(
            onClick = onRefresh,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 62.dp),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ),
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = CardDefaults.scale(focusedScale = 1.02f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                if (isLoading) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextTertiary
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = errorMessage ?: stringResource(R.string.live_tv_channels_count, channelsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (errorMessage == null) {
                            NuvioTheme.colors.TextSecondary
                        } else {
                            NuvioTheme.colors.Error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.live_tv_refresh),
                    tint = NuvioTheme.colors.TextSecondary
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(48.dp)
        ) {
            Text(
                text = "✕",
                color = NuvioTheme.colors.Error,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun LiveTvChannelRow(
    channel: LiveTvChannel,
    onPlay: () -> Unit
) {
    Card(
        onClick = onPlay,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl)
            .heightIn(min = 64.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            focusedContainerColor = NuvioTheme.colors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!channel.group.isNullOrBlank()) {
                    Text(
                        text = channel.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.live_tv_play_channel),
                tint = NuvioTheme.colors.Primary
            )
        }
    }
}

@Composable
private fun LiveTvAddPlaylistDialog(
    isBusy: Boolean,
    errorMessage: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val inputFocusRequester = remember { FocusRequester() }
    var url by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        if (!isBusy) onAdd(url)
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_add_playlist),
        subtitle = stringResource(R.string.live_tv_add_playlist_subtitle),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Card(
                onClick = { inputFocusRequester.requestFocus() },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                        shape = RoundedCornerShape(10.dp)
                    ),
                    focusedBorder = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                        cursorBrush = SolidColor(
                            if (isInputFocused) NuvioTheme.colors.Primary
                            else Color.Transparent
                        ),
                        decorationBox = { innerTextField ->
                            if (url.isBlank()) {
                                Text(
                                    text = stringResource(R.string.live_tv_add_playlist_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.Error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = { submit() },
                    enabled = !isBusy && url.isNotBlank(),
                    colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.Primary)
                ) {
                    Text(stringResource(R.string.live_tv_add_btn))
                }
            }
        }
    }
}