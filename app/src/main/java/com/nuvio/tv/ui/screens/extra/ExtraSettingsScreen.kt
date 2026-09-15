package com.nuvio.tv.ui.screens.extra

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.LayoutSettingsEvent
import com.nuvio.tv.ui.screens.settings.LayoutSettingsViewModel
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.components.RenameTabDialog

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExtraSettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToExtraLayout: () -> Unit,
    onNavigateToExtraAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    layoutSettingsViewModel: LayoutSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }

    val layoutUiState by layoutSettingsViewModel.uiState.collectAsStateWithLifecycle()
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameTabDialog(
            currentName = layoutUiState.extraTabName,
            onRename = { newName ->
                layoutSettingsViewModel.onEvent(LayoutSettingsEvent.SetExtraTabName(newName))
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = layoutUiState.extraTabName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.extra_settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }

        item(key = "rename") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.extra_settings_rename_title),
                    subtitle = layoutUiState.extraTabName,
                    onClick = { showRenameDialog = true },
                    leadingIcon = Icons.Default.Edit
                )
            }
        }

        item(key = "layout") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.extra_settings_layout_title),
                    subtitle = stringResource(R.string.extra_settings_layout_subtitle),
                    onClick = onNavigateToExtraLayout,
                    leadingIcon = Icons.Default.GridView
                )
            }
        }

        item(key = "content_discovery") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.extra_settings_addons_title),
                    subtitle = stringResource(R.string.extra_settings_addons_subtitle),
                    onClick = onNavigateToExtraAddons,
                    leadingIcon = Icons.Default.Extension
                )
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.extra_settings_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Build
                )
            }
        }
    }
}
