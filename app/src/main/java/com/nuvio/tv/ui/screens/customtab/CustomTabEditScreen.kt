package com.nuvio.tv.ui.screens.customtab

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalTvMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.TvMaterialTheme
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.IconType
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun CustomTabEditScreen(
    tabId: String,
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tab = uiState.tabs.find { it.id == tabId }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    tab?.let { currentTab ->
        var showRenameDialog by remember { mutableStateOf(false) }
        var showIconPicker by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                
            ) {
                Text(
                    text = stringResource(R.string.custom_tab_edit_title, currentTab.displayName),
                    style = TvMaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(top = 40.dp, bottom = 24.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 60.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        CustomTabEditSection(
                            title = stringResource(R.string.custom_tab_basic_settings),
                            focusRequester = focusRequester
                        ) {
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_display_name),
                                subtitle = currentTab.displayName,
                                onClick = { showRenameDialog = true }
                            )
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_icon),
                                subtitle = currentTab.icon.resourceName,
                                onClick = { showIconPicker = true }
                            )
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_enabled),
                                subtitle = if (currentTab.enabled) stringResource(R.string.on) else stringResource(R.string.off),
                                onClick = {
                                    viewModel.updateTab(currentTab.copy(enabled = !currentTab.enabled))
                                },
                                isToggle = true,
                                isChecked = currentTab.enabled
                            )
                        }
                    }
                    item {
                        CustomTabEditSection(
                            title = stringResource(R.string.custom_tab_content_settings)
                        ) {
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_select_addons),
                                subtitle = stringResource(R.string.custom_tab_select_addons_subtitle),
                                onClick = { /* TODO: navigate to addon selector */ }
                            )
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_layout),
                                subtitle = stringResource(R.string.custom_tab_layout_subtitle),
                                onClick = { /* TODO: navigate to layout settings */ }
                            )
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_catalog_order),
                                subtitle = stringResource(R.string.custom_tab_catalog_order_subtitle),
                                onClick = { /* TODO: navigate to catalog order */ }
                            )
                        }
                    }
                    item {
                        CustomTabEditSection(
                            title = stringResource(R.string.custom_tab_appearance_settings)
                        ) {
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_poster_settings),
                                subtitle = stringResource(R.string.custom_tab_poster_settings_subtitle),
                                onClick = { /* TODO: navigate to poster settings */ }
                            )
                            CustomTabEditItem(
                                title = stringResource(R.string.custom_tab_hero_settings),
                                subtitle = stringResource(R.string.custom_tab_hero_settings_subtitle),
                                onClick = { /* TODO: navigate to hero settings */ }
                            )
                        }
                    }
                }
            }
        }

        if (showRenameDialog) {
            RenameTabDialog(
                currentName = currentTab.displayName,
                onDismiss = { showRenameDialog = false },
                onConfirm = { newName ->
                    viewModel.updateTab(currentTab.copy(displayName = newName))
                    showRenameDialog = false
                }
            )
        }

        if (showIconPicker) {
            IconPickerDialog(
                currentIcon = currentTab.icon,
                onDismiss = { showIconPicker = false },
                onConfirm = { newIcon ->
                    viewModel.updateTab(currentTab.copy(icon = newIcon))
                    showIconPicker = false
                }
            )
        }
    } ?: run {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.custom_tab_not_found))
        }
    }
}

@Composable
private fun CustomTabEditSection(
    title: String,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester ?: androidx.compose.ui.focus.FocusRequester.Default)
            .padding(vertical = 8.dp),
        colors = androidx.tv.material3.CardDefaults.cardColors(
            containerColor = NuvioTheme.colors.BackgroundCard
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = TvMaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(24.dp, 16.dp, 24.dp, 0.dp)
            )
            content()
        }
    }
}

@Composable
private fun CustomTabEditItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isToggle: Boolean = false,
    isChecked: Boolean = false
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = TvMaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = TvMaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            if (isToggle) {
                androidx.tv.material3.Switch(
                    checked = isChecked,
                    onCheckedChange = { onClick() },
                    colors = androidx.tv.material3.SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Primary,
                        checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

@Composable
private fun RenameTabDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_tab_rename_title)) },
        text = {
            androidx.compose.material3.TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(16.dp),
                label = { Text(stringResource(R.string.custom_tab_name_hint)) },
                maxLines = 1,
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = NuvioTheme.colors.OnPrimary
                )
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun IconPickerDialog(
    currentIcon: IconType,
    onDismiss: () -> Unit,
    onConfirm: (IconType) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var selectedIcon by remember { mutableStateOf(currentIcon) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_tab_icon_picker_title)) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .focusRequester(focusRequester),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(IconType.values()) { icon ->
                    val isSelected = icon == selectedIcon
                    androidx.compose.material3.Button(
                        onClick = { selectedIcon = icon },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = icon.resourceName,
                                style = TvMaterialTheme.typography.bodyLarge
                            )
                            if (isSelected) {
                                androidx.tv.material3.Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = NuvioTheme.colors.Primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedIcon) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NuvioTheme.colors.Primary,
                    contentColor = NuvioTheme.colors.OnPrimary
                )
            ) {
                Text(stringResource(R.string.action_select))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}