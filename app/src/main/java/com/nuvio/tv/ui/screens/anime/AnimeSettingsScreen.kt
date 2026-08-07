package com.nuvio.tv.ui.screens.anime

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeSettingsScreen(
    viewModel: AnimeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToTracking: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isEditing by remember { mutableStateOf(false) }
    val textFieldFocusRequester = remember { FocusRequester() }
    val installButtonFocusRequester = remember { FocusRequester() }

    BackHandler { onBackPress() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.nav_anime),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.anime_settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }

        item(key = "install") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(NuvioTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    Text(
                        text = stringResource(R.string.anime_add_addon),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = { isEditing = true },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(textFieldFocusRequester),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                )
                            ),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Box(modifier = Modifier.padding(NuvioTheme.spacing.md)) {
                                BasicTextField(
                                    value = uiState.installUrl,
                                    onValueChange = viewModel::onInstallUrlChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(textFieldFocusRequester)
                                        .onFocusChanged {
                                            if (!it.isFocused && isEditing) {
                                                isEditing = false
                                                keyboardController?.hide()
                                            }
                                        },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            viewModel.installAddon()
                                            isEditing = false
                                            keyboardController?.hide()
                                            installButtonFocusRequester.requestFocus()
                                        }
                                    ),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = NuvioTheme.colors.TextPrimary
                                    ),
                                    cursorBrush = SolidColor(if (isEditing) NuvioTheme.colors.Primary else Color.Transparent),
                                    decorationBox = { innerTextField ->
                                        if (uiState.installUrl.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.addon_install_placeholder),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = NuvioTheme.colors.TextTertiary
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                viewModel.installAddon()
                                isEditing = false
                                keyboardController?.hide()
                                installButtonFocusRequester.requestFocus()
                            },
                            enabled = !uiState.isInstalling,
                            modifier = Modifier.focusRequester(installButtonFocusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                        ) {
                            Text(
                                text = if (uiState.isInstalling) {
                                    stringResource(R.string.addon_installing)
                                } else {
                                    stringResource(R.string.addon_install_btn)
                                }
                            )
                        }
                    }
                    if (uiState.error != null) {
                        Text(
                            text = uiState.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.Error
                        )
                    }
                }
            }
        }

        item(key = "integrations") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.anime_settings_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Build
                )
                SettingsActionRow(
                    title = stringResource(R.string.settings_tracking_title),
                    subtitle = stringResource(R.string.anime_settings_tracking_subtitle),
                    onClick = onNavigateToTracking,
                    leadingIcon = Icons.Default.Sync
                )
            }
        }

        item(key = "installed_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.anime_addons_section),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                if (uiState.isLoading && uiState.addons.isEmpty()) {
                    LoadingIndicator(modifier = Modifier.height(NuvioTheme.spacing.xl))
                }
            }
        }

        if (uiState.addons.isEmpty() && !uiState.isLoading) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.anime_addons_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        } else {
            itemsIndexed(
                items = uiState.addons,
                key = { _, addon -> addon.baseUrl }
            ) { index, addon ->
                AnimeAddonSettingsCard(
                    addon = addon,
                    canMoveUp = index > 0,
                    canMoveDown = index < uiState.addons.lastIndex,
                    onMoveUp = { viewModel.moveAddonUp(addon.baseUrl) },
                    onMoveDown = { viewModel.moveAddonDown(addon.baseUrl) },
                    onRemove = { viewModel.removeAddon(addon.baseUrl) },
                    onEnabledChange = { enabled -> viewModel.setAddonEnabled(addon.baseUrl, enabled) }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeAddonSettingsCard(
    addon: Addon,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = addon.displayName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (addon.version.isNotBlank()) {
                            Text(
                                text = "v${addon.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                        if (!addon.enabled) {
                            Text(
                                text = stringResource(R.string.addons_badge_disabled),
                                style = MaterialTheme.typography.labelSmall,
                                color = NuvioTheme.colors.TextSecondary
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { onEnabledChange(!addon.enabled) },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Switch(
                                checked = addon.enabled,
                                onCheckedChange = null,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuvioTheme.colors.Secondary,
                                    checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                    Button(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(imageVector = Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.cd_move_up))
                    }
                    Button(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.cd_move_down))
                    }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Error
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Text(text = stringResource(R.string.addon_remove))
                    }
                }
            }
            if (!addon.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = addon.description ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = addon.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = stringResource(R.string.addon_catalogs_types, addon.catalogs.size, addon.rawTypes.joinToString()),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
}
