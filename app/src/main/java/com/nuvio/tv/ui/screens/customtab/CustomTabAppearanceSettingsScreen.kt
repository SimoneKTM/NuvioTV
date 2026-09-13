package com.nuvio.tv.ui.screens.customtab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabAppearanceSettingsScreen(
    tabId: String,
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tab = uiState.tabs.find { it.id == tabId }

    tab?.let { currentTab ->
        val config = currentTab.config

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = stringResource(R.string.custom_tab_appearance_settings),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(top = 40.dp, bottom = 8.dp)
            )
            Text(
                text = currentTab.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 60.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.custom_tab_poster_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    NumericSettingItem(
                        title = stringResource(R.string.custom_tab_poster_width),
                        value = config.posterCardWidthDp,
                        range = 80..200,
                        onValueChange = { value ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(posterCardWidthDp = value))
                            )
                        }
                    )
                }

                item {
                    NumericSettingItem(
                        title = stringResource(R.string.custom_tab_poster_height),
                        value = config.posterCardHeightDp,
                        range = 120..300,
                        onValueChange = { value ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(posterCardHeightDp = value))
                            )
                        }
                    )
                }

                item {
                    NumericSettingItem(
                        title = stringResource(R.string.custom_tab_poster_corner_radius),
                        value = config.posterCardCornerRadiusDp,
                        range = 0..24,
                        onValueChange = { value ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(posterCardCornerRadiusDp = value))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_poster_labels),
                        checked = config.posterLabels,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(posterLabels = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_catalog_addon_name),
                        checked = config.catalogAddonName,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(catalogAddonName = enabled))
                            )
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.custom_tab_hero_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_hero_expand),
                        checked = config.focusedPosterBackdropExpand,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(focusedPosterBackdropExpand = enabled))
                            )
                        }
                    )
                }

                item {
                    NumericSettingItem(
                        title = stringResource(R.string.custom_tab_hero_expand_delay),
                        value = config.focusedPosterBackdropExpandDelay,
                        range = 0..10,
                        onValueChange = { value ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(focusedPosterBackdropExpandDelay = value))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_hero_trailer),
                        checked = config.focusedPosterBackdropTrailer,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(focusedPosterBackdropTrailer = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_hero_trailer_muted),
                        checked = config.focusedPosterBackdropTrailerMuted,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(focusedPosterBackdropTrailerMuted = enabled))
                            )
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.custom_tab_continue_watching_settings),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_cw_episode_thumbnails),
                        checked = config.useEpisodeThumbnailsInCw,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(useEpisodeThumbnailsInCw = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_cw_blur_next_up),
                        checked = config.blurContinueWatchingNextUp,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(blurContinueWatchingNextUp = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_cw_unaired_next_up),
                        checked = config.showUnairedNextUp,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(showUnairedNextUp = enabled))
                            )
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.custom_tab_content_settings_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_hide_unreleased),
                        checked = config.hideUnreleasedContent,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(hideUnreleasedContent = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_detail_trailer_button),
                        checked = config.detailPageTrailerButton,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(detailPageTrailerButton = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_prefer_external_meta),
                        checked = config.preferExternalMetaAddonDetail,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(preferExternalMetaAddonDetail = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_show_full_release_date),
                        checked = config.showFullReleaseDate,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(showFullReleaseDate = enabled))
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NumericSettingItem(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                enabled = value > range.first,
                modifier = Modifier.width(48.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = null
                )
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.Primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Button(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                enabled = value < range.last,
                modifier = Modifier.width(48.dp),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Button(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = NuvioTheme.colors.Primary,
                    checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}
