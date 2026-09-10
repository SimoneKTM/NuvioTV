package com.nuvio.tv.ui.screens.customtab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.IconType
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
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
                    style = MaterialTheme.typography.headlineMedium,
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomTabEditSection(
    title: String,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester ?: androidx.compose.ui.focus.FocusRequester.Default)
            .padding(vertical = 8.dp),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(24.dp, 16.dp, 24.dp, 0.dp)
            )
            content()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CustomTabEditItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isToggle: Boolean = false,
    isChecked: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Row(
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
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            if (isToggle) {
                Switch(
                    checked = isChecked,
                    onCheckedChange = { onClick() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Primary,
                        checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
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

    AlertDialog(
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
                colors = ButtonDefaults.colors(
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

@OptIn(ExperimentalTvMaterial3Api::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_tab_icon_picker_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .focusRequester(focusRequester),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(IconType.values()) { icon ->
                    val isSelected = icon == selectedIcon
                    Button(
                        onClick = { selectedIcon = icon },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = icon.resourceName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (isSelected) {
                                Icon(
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
                colors = ButtonDefaults.colors(
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