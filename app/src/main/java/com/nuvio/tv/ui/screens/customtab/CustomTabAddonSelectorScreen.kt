package com.nuvio.tv.ui.screens.customtab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabAddonSelectorScreen(
    tabId: String,
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tab = uiState.tabs.find { it.id == tabId }

    tab?.let { currentTab ->
        val selectedAddonIds = remember(currentTab.config.selectedAddons) {
            mutableStateOf(currentTab.config.selectedAddons.toMutableSet())
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = stringResource(R.string.custom_tab_select_addons),
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
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "select_all") {
                    val allSelected = selectedAddonIds.value.isEmpty()
                    Button(
                        onClick = {
                            selectedAddonIds.value = mutableSetOf()
                            viewModel.updateTab(
                                currentTab.copy(
                                    config = currentTab.config.copy(selectedAddons = emptyList())
                                )
                            )
                        },
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
                            Column {
                                Text(
                                    text = stringResource(R.string.custom_tab_addons_all),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NuvioTheme.colors.TextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.custom_tab_addons_all_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            Switch(
                                checked = allSelected,
                                onCheckedChange = {
                                    selectedAddonIds.value = mutableSetOf()
                                    viewModel.updateTab(
                                        currentTab.copy(
                                            config = currentTab.config.copy(selectedAddons = emptyList())
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuvioTheme.colors.Primary,
                                    checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }

                items(
                    items = uiState.availableAddons,
                    key = { it.id }
                ) { addon ->
                    val isSelected = selectedAddonIds.value.isEmpty() ||
                        addon.id in selectedAddonIds.value
                    Button(
                        onClick = {
                            val current = selectedAddonIds.value.toMutableSet()
                            if (current.isEmpty()) {
                                current.addAll(uiState.availableAddons.map { it.id })
                            }
                            if (addon.id in current) {
                                current.remove(addon.id)
                            } else {
                                current.add(addon.id)
                            }
                            selectedAddonIds.value = current
                            viewModel.updateTab(
                                currentTab.copy(
                                    config = currentTab.config.copy(
                                        selectedAddons = current.toList()
                                    )
                                )
                            )
                        },
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = addon.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NuvioTheme.colors.TextPrimary
                                )
                                Text(
                                    text = addon.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NuvioTheme.colors.TextTertiary,
                                    maxLines = 1
                                )
                            }
                            Switch(
                                checked = isSelected,
                                onCheckedChange = {
                                    val current = selectedAddonIds.value.toMutableSet()
                                    if (current.isEmpty()) {
                                        current.addAll(uiState.availableAddons.map { it.id })
                                    }
                                    if (addon.id in current) {
                                        current.remove(addon.id)
                                    } else {
                                        current.add(addon.id)
                                    }
                                    selectedAddonIds.value = current
                                    viewModel.updateTab(
                                        currentTab.copy(
                                            config = currentTab.config.copy(
                                                selectedAddons = current.toList()
                                            )
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NuvioTheme.colors.Primary,
                                    checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
