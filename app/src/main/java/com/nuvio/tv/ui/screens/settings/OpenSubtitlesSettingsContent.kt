@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import com.nuvio.tv.R
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.components.NuvioDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person

@Composable
fun OpenSubtitlesSettingsContent(
    viewModel: OpenSubtitlesSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showLanguagesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.toggleError) {
        val error = uiState.toggleError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.opensubtitles_title),
            subtitle = stringResource(R.string.settings_opensubtitles_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val state = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "opensubtitles_direct_enable") {
                        SettingsToggleRow(
                            title = stringResource(R.string.opensubtitles_direct_enable_title),
                            subtitle = stringResource(R.string.opensubtitles_direct_enable_subtitle),
                            checked = uiState.enabledDirect,
                            onToggle = { viewModel.setDirectEnabled(!uiState.enabledDirect) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }

                    if (uiState.enabledDirect && !uiState.hasApiKey) {
                        item(key = "opensubtitles_direct_api_key_hint") {
                            Text(
                                text = stringResource(R.string.opensubtitles_direct_no_api_key_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextTertiary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md)
                            )
                        }
                    }

                    item(key = "opensubtitles_direct_api_key") {
                        SettingsActionRow(
                            leadingIcon = Icons.Default.Key,
                            title = stringResource(R.string.opensubtitles_api_key_title),
                            subtitle = stringResource(R.string.opensubtitles_api_key_subtitle),
                            value = maskSecret(uiState.hasApiKey, stringResource(R.string.opensubtitles_not_set)),
                            onClick = { showApiKeyDialog = true },
                            enabled = uiState.enabledDirect
                        )
                    }

                    item(key = "opensubtitles_direct_username") {
                        SettingsActionRow(
                            leadingIcon = Icons.Default.Person,
                            title = stringResource(R.string.opensubtitles_username_title),
                            subtitle = stringResource(R.string.opensubtitles_username_subtitle),
                            value = uiState.username.ifBlank { stringResource(R.string.opensubtitles_not_set) },
                            onClick = { showUsernameDialog = true },
                            enabled = uiState.enabledDirect
                        )
                    }

                    item(key = "opensubtitles_direct_password") {
                        SettingsActionRow(
                            leadingIcon = Icons.Default.Lock,
                            title = stringResource(R.string.opensubtitles_password_title),
                            subtitle = stringResource(R.string.opensubtitles_password_subtitle),
                            value = if (uiState.hasUserCredentials) {
                                "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
                            } else {
                                stringResource(R.string.opensubtitles_not_set)
                            },
                            onClick = { showPasswordDialog = true },
                            enabled = uiState.enabledDirect
                        )
                    }

                    item(key = "opensubtitles_direct_languages") {
                        SettingsActionRow(
                            leadingIcon = Icons.Default.Language,
                            title = stringResource(R.string.opensubtitles_languages_title),
                            subtitle = stringResource(R.string.opensubtitles_languages_subtitle),
                            value = if (uiState.languages.isNotEmpty()) {
                                uiState.languages.sorted().joinToString(", ")
                            } else {
                                stringResource(R.string.opensubtitles_not_set)
                            },
                            onClick = { showLanguagesDialog = true },
                            enabled = uiState.enabledDirect
                        )
                    }

                    item(key = "opensubtitles_legacy_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.opensubtitles_legacy_addon_section),
                                style = MaterialTheme.typography.labelLarge,
                                color = NuvioTheme.colors.TextSecondary
                            )
                            Text(
                                text = stringResource(R.string.opensubtitles_legacy_addon_section_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextTertiary
                            )
                        }
                    }

                    item(key = "opensubtitles_enable") {
                        SettingsToggleRow(
                            title = stringResource(R.string.opensubtitles_enable_title),
                            subtitle = stringResource(R.string.opensubtitles_enable_subtitle),
                            checked = uiState.isInstalled && uiState.isEnabled,
                            onToggle = { viewModel.setEnabled(!uiState.isEnabled) },
                            enabled = !uiState.isBusy
                        )
                    }

                    if (uiState.isInstalled) {
                        item(key = "opensubtitles_url") {
                            SettingsActionRow(
                                title = stringResource(R.string.opensubtitles_url_title),
                                subtitle = stringResource(R.string.opensubtitles_url_subtitle),
                                value = uiState.addonUrl,
                                onClick = { viewModel.reinstallAddon() },
                                enabled = !uiState.isBusy
                            )
                        }
                    } else {
                        item(key = "opensubtitles_reinstall") {
                            SettingsActionRow(
                                title = stringResource(R.string.opensubtitles_reinstall_title),
                                subtitle = stringResource(R.string.opensubtitles_reinstall_subtitle),
                                onClick = { viewModel.reinstallAddon() },
                                enabled = !uiState.isBusy
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = state)
            }
        }
    }

    if (showApiKeyDialog) {
        OpenSubtitlesTextDialog(
            title = stringResource(R.string.opensubtitles_api_key_title),
            currentValue = "",
            placeholder = stringResource(R.string.opensubtitles_api_key_placeholder),
            keyboardType = KeyboardType.Text,
            onSaved = { value ->
                viewModel.setApiKey(value)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showUsernameDialog) {
        OpenSubtitlesTextDialog(
            title = stringResource(R.string.opensubtitles_username_title),
            currentValue = uiState.username,
            placeholder = stringResource(R.string.opensubtitles_username_placeholder),
            keyboardType = KeyboardType.Text,
            onSaved = { value ->
                viewModel.setUsername(value)
                showUsernameDialog = false
            },
            onDismiss = { showUsernameDialog = false }
        )
    }

    if (showPasswordDialog) {
        OpenSubtitlesTextDialog(
            title = stringResource(R.string.opensubtitles_password_title),
            currentValue = "",
            placeholder = stringResource(R.string.opensubtitles_password_placeholder),
            keyboardType = KeyboardType.Password,
            isPassword = true,
            onSaved = { value ->
                viewModel.setPassword(value)
                showPasswordDialog = false
            },
            onDismiss = { showPasswordDialog = false }
        )
    }

    if (showLanguagesDialog) {
        OpenSubtitlesLanguagesDialog(
            selectedLanguages = uiState.languages,
            onToggleLanguage = viewModel::toggleLanguage,
            onDismiss = { showLanguagesDialog = false }
        )
    }
}

@Composable
private fun OpenSubtitlesTextDialog(
    title: String,
    currentValue: String,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        width = 700.dp
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
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            onSaved(value)
                        }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSaved(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun OpenSubtitlesLanguagesDialog(
    selectedLanguages: Set<String>,
    onToggleLanguage: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sorted = remember {
        AVAILABLE_SUBTITLE_LANGUAGES.sortedBy { it.displayName.lowercase() }
    }
    val listState = rememberLazyListState()

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.opensubtitles_languages_title),
        subtitle = stringResource(R.string.opensubtitles_languages_subtitle),
        width = 560.dp
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(sorted, key = { it.code }) { language ->
                SettingsToggleRow(
                    title = language.displayName,
                    subtitle = language.code.uppercase(),
                    checked = language.code in selectedLanguages,
                    onToggle = { onToggleLanguage(language.code, language.code !in selectedLanguages) }
                )
            }
        }
    }
}

private fun maskSecret(hasValue: Boolean, notSetLabel: String): String =
    if (hasValue) "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" else notSetLabel