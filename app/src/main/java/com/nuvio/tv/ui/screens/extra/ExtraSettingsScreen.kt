package com.nuvio.tv.ui.screens.extra

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExtraSettingsScreen(
    onBackPress: () -> Unit,
    onNavigateToExtraLayout: () -> Unit,
    onNavigateToExtraAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit
) {
    BackHandler { onBackPress() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 36.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = stringResource(R.string.extra_settings_title),
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
                if (AppFeaturePolicy.pluginsEnabled) {
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
}
