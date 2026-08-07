@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.anilist.AniListConnectionMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun AniListAccountDialog(
    state: AniListSettingsUiState,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        if (state.mode == AniListConnectionMode.CONNECTED) {
            AniListConnectedContent(
                state = state,
                onDismiss = onDismiss,
                onSync = onSync,
                onDisconnect = onDisconnect
            )
        } else {
            AniListConnectContent(
                state = state,
                onStartQrLogin = onStartQrLogin,
                onRetryQrLogin = onRetryQrLogin,
                onCancelQrLogin = onCancelQrLogin,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun AniListConnectedContent(
    state: AniListSettingsUiState,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    AniListWordmarkHeader()
    Text(
        text = stringResource(
            R.string.anilist_connected_as,
            state.username ?: stringResource(R.string.anilist_user_fallback)
        ),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(R.string.anilist_description),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary
    )
    val visibleStatus = state.errorMessage ?: state.statusMessage
    if (!visibleStatus.isNullOrBlank()) {
        Text(
            text = visibleStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.errorMessage == null) {
                NuvioTheme.colors.TextSecondary
            } else {
                NuvioTheme.colors.Error
            }
        )
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_close),
            onClick = onDismiss
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.anilist_sync_now),
            onClick = onSync,
            primary = true,
            enabled = !state.isLoading
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.anilist_disconnect),
            onClick = onDisconnect,
            enabled = !state.isLoading
        )
    }
}

@Composable
private fun AniListConnectContent(
    state: AniListSettingsUiState,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    AniListWordmarkHeader()
    Text(
        text = stringResource(R.string.anilist_connect_instruction),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary
    )
    TrackerQrLoginSection(
        qrLogin = state.qrLogin,
        onStart = onStartQrLogin,
        onRetry = onRetryQrLogin,
        onCancel = onCancelQrLogin
    )
    if (!state.errorMessage.isNullOrBlank()) {
        Text(
            text = state.errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.Error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
        SettingsDialogActionButton(
            text = stringResource(R.string.action_cancel),
            onClick = onDismiss
        )
    }
}

@Composable
private fun AniListWordmarkHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Image(
            painter = rememberRawSvgPainter(R.raw.anilist_icon, 52.dp),
            contentDescription = stringResource(R.string.cd_anilist_logo),
            modifier = Modifier.size(52.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.anilist_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.anilist_description),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
    }
}