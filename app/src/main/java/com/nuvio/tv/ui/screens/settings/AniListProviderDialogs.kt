@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onConnectToken: (String) -> Unit,
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
                onConnectToken = onConnectToken,
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
    onConnectToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state.qrLogin.isConfigured) {
        TrackerQrLoginSection(
            qrLogin = state.qrLogin,
            providerName = stringResource(R.string.anilist_name),
            instruction = stringResource(R.string.anilist_connect_instruction),
            onStart = onStartQrLogin,
            onRetry = onRetryQrLogin,
            qrOverlayLogo = painterResource(R.drawable.anilist_icon),
            logoContent = { AniListWordmarkHeader() }
        )
    } else {
        AniListWordmarkHeader()
        Text(
            text = stringResource(R.string.anilist_connect_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        TrackerLocalQrSection(
            authorizeUrl = state.authorizeUrl,
            onConnectToken = onConnectToken,
            qrOverlayLogo = painterResource(R.drawable.anilist_icon)
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
private fun AniListWordmarkHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.anilist_icon),
            contentDescription = stringResource(R.string.cd_anilist_logo),
            modifier = Modifier.size(34.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        AniListOutlinedWordmark()
    }
}

@Composable
private fun AniListOutlinedWordmark() {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val headline = MaterialTheme.typography.headlineLarge
    val style = remember(headline) {
        headline.copy(
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            fontSize = 36.sp
        )
    }
    val text = "AniList"
    val layout = remember(text, style) {
        textMeasurer.measure(AnnotatedString(text), style = style)
    }
    val strokeWidth = 3.dp
    val strokePx = with(density) { strokeWidth.toPx() }
    Canvas(
        modifier = Modifier
            .width(with(density) { (layout.size.width + strokePx).toDp() })
            .height(with(density) { (layout.size.height + strokePx).toDp() })
    ) {
        val topLeft = Offset(
            x = (size.width - layout.size.width) / 2f,
            y = (size.height - layout.size.height) / 2f
        )
        drawText(
            layout,
            topLeft = topLeft,
            color = Color(0xFF02A9FF),
            drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
        drawText(layout, topLeft = topLeft, color = Color.White)
    }
}