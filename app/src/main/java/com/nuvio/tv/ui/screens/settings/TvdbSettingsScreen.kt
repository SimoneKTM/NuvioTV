@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.local.normalizeTvdbLanguage
import com.nuvio.tv.ui.components.NuvioDialog

@Composable
fun TvdbSettingsContent(
    viewModel: TvdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    TvdbSettingsContentShared(
        controller = viewModel,
        headerTitleRes = R.string.tvdb_settings_title,
        headerSubtitleRes = R.string.tvdb_settings_subtitle,
        initialFocusRequester = initialFocusRequester
    )
}

@Composable
fun AnimeTvdbSettingsContent(
    viewModel: AnimeTvdbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    TvdbSettingsContentShared(
        controller = viewModel,
        headerTitleRes = R.string.tvdb_anime_settings_title,
        headerSubtitleRes = R.string.tvdb_anime_settings_subtitle,
        initialFocusRequester = initialFocusRequester
    )
}

@Composable
private fun TvdbSettingsContentShared(
    controller: TvdbSettingsController,
    headerTitleRes: Int,
    headerSubtitleRes: Int,
    initialFocusRequester: FocusRequester?
) {
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    val enrichmentControlsEnabled = uiState.enabled && uiState.apiKey.isNotBlank()

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(headerTitleRes),
            subtitle = stringResource(headerSubtitleRes)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val tvdbState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = tvdbState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "tvdb_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_enable_title),
                            subtitle = stringResource(R.string.tvdb_enable_subtitle),
                            checked = uiState.enabled,
                            enabled = uiState.apiKey.isNotBlank(),
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleEnabled(!uiState.enabled)) },
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

                    item(key = "tvdb_api_key") {
                        SettingsActionRow(
                            title = stringResource(R.string.tvdb_api_key_title),
                            subtitle = stringResource(R.string.tvdb_api_key_subtitle),
                            value = maskApiKey(uiState.apiKey, stringResource(R.string.tvdb_not_set)),
                            onClick = { showApiKeyDialog = true }
                        )
                    }

                    item(key = "tvdb_language") {
                        SettingsActionRow(
                            title = stringResource(R.string.tvdb_language_title),
                            subtitle = stringResource(R.string.tvdb_language_subtitle),
                            value = uiState.language.ifBlank { "en" },
                            onClick = { showLanguageDialog = true },
                            enabled = uiState.apiKey.isNotBlank()
                        )
                    }

                    item(key = "tvdb_trailers") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_trailers_title),
                            subtitle = stringResource(R.string.tvdb_trailers_subtitle),
                            checked = uiState.useTrailers,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleTrailers(!uiState.useTrailers)) }
                        )
                    }

                    item(key = "tvdb_artwork") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_artwork_title),
                            subtitle = stringResource(R.string.tvdb_artwork_subtitle),
                            checked = uiState.useArtwork,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleArtwork(!uiState.useArtwork)) }
                        )
                    }

                    item(key = "tvdb_basic_info") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_basic_info_title),
                            subtitle = stringResource(R.string.tvdb_basic_info_subtitle),
                            checked = uiState.useBasicInfo,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleBasicInfo(!uiState.useBasicInfo)) }
                        )
                    }

                    item(key = "tvdb_credits") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_credits_title),
                            subtitle = stringResource(R.string.tvdb_credits_subtitle),
                            checked = uiState.useCredits,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleCredits(!uiState.useCredits)) }
                        )
                    }

                    item(key = "tvdb_episodes") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_episodes_title),
                            subtitle = stringResource(R.string.tvdb_episodes_subtitle),
                            checked = uiState.useEpisodes,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleEpisodes(!uiState.useEpisodes)) }
                        )
                    }

                    item(key = "tvdb_season_posters") {
                        SettingsToggleRow(
                            title = stringResource(R.string.tvdb_season_posters_title),
                            subtitle = stringResource(R.string.tvdb_season_posters_subtitle),
                            checked = uiState.useSeasonPosters,
                            enabled = enrichmentControlsEnabled,
                            onToggle = { controller.onEvent(TvdbSettingsEvent.ToggleSeasonPosters(!uiState.useSeasonPosters)) }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = tvdbState)
            }
        }
    }

    if (showApiKeyDialog) {
        TvdbApiKeyDialog(
            currentValue = uiState.apiKey,
            controller = controller,
            onSaved = { showApiKeyDialog = false },
            onClear = {
                controller.onEvent(TvdbSettingsEvent.SetApiKey(""))
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showLanguageDialog) {
        TvdbLanguageDialog(
            currentValue = uiState.language,
            controller = controller,
            onDismiss = { showLanguageDialog = false }
        )
    }
}

@Composable
private fun TvdbApiKeyDialog(
    currentValue: String,
    controller: TvdbSettingsController,
    onSaved: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tvdb_api_key_dialog_title),
        subtitle = stringResource(R.string.tvdb_api_key_dialog_subtitle),
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.tvdb_api_key_dialog_placeholder),
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
                onClick = onClear,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_clear))
            }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = {
                    controller.onEvent(TvdbSettingsEvent.SetApiKey(value.trim()))
                    onSaved()
                },
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
private fun TvdbLanguageDialog(
    currentValue: String,
    controller: TvdbSettingsController,
    onDismiss: () -> Unit
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tvdb_language_dialog_title),
        subtitle = stringResource(R.string.tvdb_language_dialog_subtitle),
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { keyboardController?.hide() }
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NuvioTheme.colors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = stringResource(R.string.tvdb_language_dialog_placeholder),
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
                onClick = {
                    controller.onEvent(TvdbSettingsEvent.SetLanguage(normalizeTvdbLanguage(value)))
                    onDismiss()
                },
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

private fun maskApiKey(key: String, notSetLabel: String): String {
    val trimmed = key.trim()
    if (trimmed.isBlank()) return notSetLabel
    return if (trimmed.length <= 4) "••••" else "••••••${trimmed.takeLast(4)}"
}
