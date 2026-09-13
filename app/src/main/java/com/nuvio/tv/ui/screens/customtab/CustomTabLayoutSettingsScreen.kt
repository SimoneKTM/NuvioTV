package com.nuvio.tv.ui.screens.customtab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabLayoutSettingsScreen(
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
                text = stringResource(R.string.custom_tab_layout),
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
                        text = stringResource(R.string.custom_tab_layout_home),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    LayoutOptionButton(
                        title = stringResource(R.string.layout_modern),
                        subtitle = stringResource(R.string.layout_modern_subtitle),
                        isSelected = config.homeLayout == HomeLayout.MODERN,
                        onClick = {
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(homeLayout = HomeLayout.MODERN))
                            )
                        }
                    )
                }

                item {
                    LayoutOptionButton(
                        title = stringResource(R.string.layout_classic),
                        subtitle = stringResource(R.string.layout_classic_subtitle),
                        isSelected = config.homeLayout == HomeLayout.CLASSIC,
                        onClick = {
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(homeLayout = HomeLayout.CLASSIC))
                            )
                        }
                    )
                }

                item {
                    LayoutOptionButton(
                        title = stringResource(R.string.layout_grid),
                        subtitle = stringResource(R.string.layout_grid_subtitle),
                        isSelected = config.homeLayout == HomeLayout.GRID,
                        onClick = {
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(homeLayout = HomeLayout.GRID))
                            )
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.custom_tab_layout_sections),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_hero_section),
                        checked = config.showHeroSection,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(showHeroSection = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_continue_watching),
                        checked = config.showContinueWatching,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(showContinueWatching = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_catalog_type_suffix),
                        checked = config.catalogTypeSuffix,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(catalogTypeSuffix = enabled))
                            )
                        }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.custom_tab_layout_options),
                        style = MaterialTheme.typography.labelLarge,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_modern_landscape),
                        checked = config.modernLandscapePosters,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(modernLandscapePosters = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_full_screen_backdrop),
                        checked = config.heroFullScreenBackdrop,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(heroFullScreenBackdrop = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_classic_focus_gradient),
                        checked = config.classicFocusGradient,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(classicFocusGradient = enabled))
                            )
                        }
                    )
                }

                item {
                    ToggleItem(
                        title = stringResource(R.string.custom_tab_follow_addons_order),
                        checked = config.followAddonsOrder,
                        onCheckedChange = { enabled ->
                            viewModel.updateTab(
                                currentTab.copy(config = config.copy(followAddonsOrder = enabled))
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
private fun LayoutOptionButton(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
            contentColor = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
            if (isSelected) {
                Text(
                    text = "\u2713",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.Primary
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
