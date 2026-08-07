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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.nuvio.tv.ui.screens.addon.ConfirmAddonChangesDialog
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeAddonManagerScreen(
    viewModel: AnimeSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var isEditing by remember { mutableStateOf(false) }
    val surfaceFocusRequester = remember { FocusRequester() }
    val textFieldFocusRequester = remember { FocusRequester() }
    val installButtonFocusRequester = remember { FocusRequester() }

    BackHandler { onBackPress() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val requestInputBarFocus = {
        coroutineScope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        if (!uiState.isQrModeActive && uiState.pendingChange == null && !isEditing) {
            requestInputBarFocus()
        }
    }

    DisposableEffect(lifecycleOwner, uiState.isQrModeActive, uiState.pendingChange, isEditing) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                !uiState.isQrModeActive &&
                uiState.pendingChange == null &&
                !isEditing
            ) {
                requestInputBarFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopQrMode() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.anime_settings_addons_title),
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.anime_add_addon),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                onClick = { isEditing = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(surfaceFocusRequester),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundElevated,
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
                            Button(
                                onClick = viewModel::refreshAddons,
                                enabled = !uiState.isRefreshing,
                                colors = ButtonDefaults.colors(
                                    containerColor = NuvioTheme.colors.BackgroundCard,
                                    contentColor = NuvioTheme.colors.TextSecondary,
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                    focusedContentColor = NuvioTheme.colors.Primary
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                            ) {
                                if (uiState.isRefreshing) {
                                    LoadingIndicator(modifier = Modifier.height(NuvioTheme.spacing.xl))
                                } else {
                                    Icon(imageVector = Icons.Default.Sync, contentDescription = stringResource(R.string.anime_settings_refresh_title))
                                }
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                                Text(
                                    text = if (uiState.isRefreshing) {
                                        stringResource(R.string.anime_settings_refreshing)
                                    } else {
                                        stringResource(R.string.anime_settings_refresh_title)
                                    }
                                )
                            }
                        }
                        if (uiState.error != null) {
                            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                            Text(
                                text = uiState.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.Error
                            )
                        }
                    }
                }
            }

            item(key = "installed") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    if (uiState.isLoading && uiState.addons.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.lg),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator(modifier = Modifier.height(NuvioTheme.spacing.xl))
                        }
                    } else if (uiState.addons.isEmpty()) {
                        Text(
                            text = stringResource(R.string.anime_addons_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(NuvioTheme.spacing.lg)
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(start = NuvioTheme.spacing.lg, end = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                        ) {
                            uiState.addons.forEachIndexed { index, addon ->
                                AnimeAddonSettingsCard(
                                    addon = addon,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < uiState.addons.lastIndex,
                                    onMoveUp = { viewModel.moveAddonUp(addon.baseUrl) },
                                    onMoveDown = { viewModel.moveAddonDown(addon.baseUrl) },
                                    onRemove = { viewModel.removeAddon(addon.baseUrl) },
                                    onEnabledChange = { enabled -> viewModel.setAddonEnabled(addon.baseUrl, enabled) }
                                )
                                if (index < uiState.addons.lastIndex) {
                                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
                                }
                            }
                        }
                    }
                }
            }

            item(key = "manage") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsActionRow(
                        title = stringResource(R.string.anime_settings_manage_phone_title),
                        subtitle = stringResource(R.string.anime_settings_manage_phone_subtitle),
                        onClick = viewModel::startQrMode,
                        leadingIcon = Icons.Default.QrCode2
                    )
                }
            }
        }

        if (uiState.isQrModeActive) {
            Popup(properties = PopupProperties(focusable = true)) {
                QrCodeOverlay(
                    qrBitmap = uiState.qrCodeBitmap,
                    serverUrl = uiState.serverUrl,
                    instruction = stringResource(R.string.addon_qr_scan_instruction),
                    onClose = viewModel::stopQrMode,
                    hasPendingChange = uiState.pendingChange != null
                )
            }
        }

        if (uiState.pendingChange != null) {
            Popup(properties = PopupProperties(focusable = true)) {
                uiState.pendingChange?.let { pending ->
                    ConfirmAddonChangesDialog(
                        pendingChange = pending,
                        onConfirm = viewModel::confirmPendingChange,
                        onReject = viewModel::rejectPendingChange
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AnimeAddonSettingsCard(
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