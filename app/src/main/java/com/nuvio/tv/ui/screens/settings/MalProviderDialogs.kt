@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mal.MalConnectionMode
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MalAccountDialog(
    state: MalSettingsUiState,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onConnectToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        if (state.mode == MalConnectionMode.CONNECTED) {
            MalConnectedContent(
                state = state,
                onDismiss = onDismiss,
                onSync = onSync,
                onDisconnect = onDisconnect
            )
        } else {
            MalConnectContent(
                state = state,
                onStartQrLogin = onStartQrLogin,
                onRetryQrLogin = onRetryQrLogin,
                onCancelQrLogin = onCancelQrLogin,
                onConnectToken = onConnectToken,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun MalConnectedContent(
    state: MalSettingsUiState,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    MalWordmarkHeader()
    Text(
        text = stringResource(
            R.string.mal_connected_as,
            state.username ?: stringResource(R.string.mal_user_fallback)
        ),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(R.string.mal_description),
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
            text = stringResource(R.string.mal_sync_now),
            onClick = onSync,
            primary = true,
            enabled = !state.isLoading
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.mal_disconnect),
            onClick = onDisconnect,
            enabled = !state.isLoading
        )
    }
}

@Composable
private fun MalConnectContent(
    state: MalSettingsUiState,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onConnectToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state.qrLogin.isConfigured) {
        TrackerQrLoginSection(
            qrLogin = state.qrLogin,
            providerName = stringResource(R.string.mal_name),
            logo = rememberRemoteLogoPainter(
                url = "https://upload.wikimedia.org/wikipedia/commons/9/9b/MyAnimeList_favicon.svg",
                fallbackRes = R.drawable.mal_logo_app,
                targetSize = 40.dp
            ),
            logoContentDescription = stringResource(R.string.cd_mal_logo),
            instruction = stringResource(R.string.mal_connect_instruction),
            onStart = onStartQrLogin,
            onRetry = onRetryQrLogin,
            qrOverlayLogo = painterResource(R.drawable.mal_logo_wordmark)
        )
    } else {
        MalWordmarkHeader()
        Text(
            text = stringResource(R.string.mal_connect_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        TrackerLocalQrSection(
            authorizeUrl = state.authorizeUrl,
            onConnectToken = onConnectToken,
            qrOverlayLogo = painterResource(R.drawable.mal_logo_wordmark)
        )
    }
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
private fun MalWordmarkHeader() {
    Image(
        painter = rememberRemoteLogoPainter(
            url = "https://upload.wikimedia.org/wikipedia/commons/9/9b/MyAnimeList_favicon.svg",
            fallbackRes = R.drawable.mal_logo_app,
            targetSize = 40.dp
        ),
        contentDescription = stringResource(R.string.cd_mal_logo),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        contentScale = ContentScale.Fit
    )
}