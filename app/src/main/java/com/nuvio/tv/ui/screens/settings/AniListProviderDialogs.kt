@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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
    onConnect: (String) -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                onOpenBrowser = {
                    val url = state.authorizeUrl
                    if (!url.isNullOrBlank()) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                    }
                },
                onConnect = onConnect,
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
    onOpenBrowser: () -> Unit,
    onConnect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    AniListWordmarkHeader()
    Text(
        text = stringResource(R.string.anilist_connect_instruction),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    isAniListTokenSelectKey(native.keyCode)
                ) {
                    editing = true
                    keyboardController?.show()
                }
                false
            },
        readOnly = !editing,
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                editing = false
                keyboardController?.hide()
            }
        ),
        label = { Text(stringResource(R.string.anilist_token_label)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = NuvioTheme.colors.TextPrimary,
            unfocusedTextColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
            focusedBorderColor = NuvioTheme.colors.FocusRing,
            unfocusedBorderColor = NuvioTheme.colors.Border,
            focusedLabelColor = NuvioTheme.colors.TextSecondary,
            unfocusedLabelColor = NuvioTheme.colors.TextTertiary,
            cursorColor = NuvioTheme.colors.FocusRing
        )
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
        SettingsDialogActionButton(
            text = stringResource(R.string.anilist_open_authorize),
            onClick = onOpenBrowser,
            enabled = state.credentialsConfigured &&
                !state.authorizeUrl.isNullOrBlank() &&
                !state.isLoading
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.anilist_connect),
            onClick = {
                editing = false
                keyboardController?.hide()
                onConnect(token)
            },
            primary = true,
            enabled = token.isNotBlank() && !state.isLoading
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

private fun isAniListTokenSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER