@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.kitsu.KitsuConnectionMode
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun KitsuAccountDialog(
    state: KitsuSettingsUiState,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onSubmitCredentials: (String, String) -> Unit,
    onConnectToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        if (state.mode == KitsuConnectionMode.CONNECTED) {
            KitsuConnectedContent(
                state = state,
                onDismiss = onDismiss,
                onSync = onSync,
                onDisconnect = onDisconnect
            )
        } else {
            KitsuConnectContent(
                state = state,
                onStartQrLogin = onStartQrLogin,
                onRetryQrLogin = onRetryQrLogin,
                onCancelQrLogin = onCancelQrLogin,
                onSubmitCredentials = onSubmitCredentials,
                onConnectToken = onConnectToken,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun KitsuConnectedContent(
    state: KitsuSettingsUiState,
    onDismiss: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit
) {
    KitsuWordmarkHeader()
    Text(
        text = stringResource(
            R.string.kitsu_connected_as,
            state.username ?: stringResource(R.string.kitsu_user_fallback)
        ),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(R.string.kitsu_description),
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
            text = stringResource(R.string.kitsu_sync_now),
            onClick = onSync,
            primary = true,
            enabled = !state.isLoading
        )
        SettingsDialogActionButton(
            text = stringResource(R.string.kitsu_disconnect),
            onClick = onDisconnect,
            enabled = !state.isLoading
        )
    }
}

@Composable
private fun KitsuConnectContent(
    state: KitsuSettingsUiState,
    onStartQrLogin: () -> Unit,
    onRetryQrLogin: () -> Unit,
    onCancelQrLogin: () -> Unit,
    onSubmitCredentials: (String, String) -> Unit,
    onConnectToken: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (state.qrLogin.isConfigured) {
        KitsuCredentialLoginSection(
            qrLogin = state.qrLogin,
            onSubmitCredentials = onSubmitCredentials,
            onStart = onStartQrLogin,
            onRetry = onRetryQrLogin
        )
    } else {
        KitsuWordmarkHeader()
        Text(
            text = stringResource(R.string.kitsu_connect_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        TrackerLocalQrSection(
            authorizeUrl = state.authorizeUrl,
            onConnectToken = onConnectToken
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
private fun KitsuCredentialLoginSection(
    qrLogin: TrackerQrLoginState,
    onSubmitCredentials: (String, String) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit
) {
    LaunchedEffect(qrLogin.session, qrLogin.isStarting, qrLogin.errorMessage) {
        if (qrLogin.session == null && !qrLogin.isStarting && qrLogin.errorMessage == null) {
            onStart()
        }
    }
    KitsuWordmarkHeader()
    Text(
        text = stringResource(R.string.kitsu_connect_credential_instruction),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    val session = qrLogin.session
    when {
        qrLogin.isStarting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    NuvioTheme.spacing.md,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(28.dp))
                Text(
                    text = stringResource(R.string.tracking_connecting_provider, stringResource(R.string.kitsu_name)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        session != null -> {
            CredentialForm(
                qrLogin = qrLogin,
                onSubmitCredentials = onSubmitCredentials,
                onRetry = onRetry
            )
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                if (!qrLogin.errorMessage.isNullOrBlank()) {
                    Text(
                        text = qrLogin.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
                        SettingsDialogActionButton(
                            text = stringResource(R.string.action_retry),
                            onClick = onRetry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialForm(
    qrLogin: TrackerQrLoginState,
    onSubmitCredentials: (String, String) -> Unit,
    onRetry: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var usernameFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        CredentialField(
            value = username,
            onValueChange = { username = it },
            placeholder = stringResource(R.string.kitsu_credential_username_placeholder),
            isFocused = usernameFocused,
            onFocusChanged = { usernameFocused = it },
            focusRequester = usernameFocusRequester,
            imeAction = ImeAction.Next,
            keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() })
        )
        CredentialField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.kitsu_credential_password_placeholder),
            isFocused = passwordFocused,
            onFocusChanged = { passwordFocused = it },
            focusRequester = passwordFocusRequester,
            imeAction = ImeAction.Done,
            isPassword = true,
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    onSubmitCredentials(username, password)
                }
            )
        )
        if (qrLogin.isSubmitting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    NuvioTheme.spacing.md,
                    Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingIndicator(modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(R.string.kitsu_credential_submitting),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        val status = qrLogin.errorMessage ?: qrLogin.statusMessage
        if (!status.isNullOrBlank() && !qrLogin.isSubmitting) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (qrLogin.errorMessage == null) {
                    NuvioTheme.colors.TextSecondary
                } else {
                    NuvioTheme.colors.Error
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (qrLogin.errorMessage != null && !qrLogin.isSubmitting) {
            SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    keyboardController?.hide()
                    onSubmitCredentials(username, password)
                },
                enabled = username.isNotBlank() && password.isNotBlank() && !qrLogin.isSubmitting,
                colors = ButtonDefaults.colors(containerColor = NuvioTheme.colors.Primary)
            ) {
                Text(stringResource(R.string.kitsu_credential_sign_in))
            }
        }
    }
}

@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    keyboardActions: KeyboardActions,
    isPassword: Boolean = false
) {
    Card(
        onClick = { focusRequester.requestFocus() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused || it.hasFocus) },
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
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = keyboardActions,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                cursorBrush = SolidColor(
                    if (isFocused) NuvioTheme.colors.Primary
                    else Color.Transparent
                ),
                decorationBox = { innerTextField ->
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                    innerTextField()
                }
            )
        }
    }
}

@Composable
private fun KitsuWordmarkHeader() {
    Image(
        painter = painterResource(R.drawable.kitsu_logo),
        contentDescription = stringResource(R.string.cd_kitsu_logo),
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        contentScale = ContentScale.Fit
    )
}