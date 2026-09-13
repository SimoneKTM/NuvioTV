package com.nuvio.tv.ui.screens.customtab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabCatalogOrderScreen(
    tabId: String,
    viewModel: CustomTabSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tab = uiState.tabs.find { it.id == tabId }

    tab?.let { currentTab ->
        val config = currentTab.config

        val catalogOrder = remember(config.catalogOrder) {
            mutableStateListOf(*config.catalogOrder.toTypedArray())
        }

        val disabledCatalogs = remember(config.disabledCatalogs) {
            mutableStateOf(config.disabledCatalogs.toMutableSet())
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = stringResource(R.string.custom_tab_catalog_order),
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = catalogOrder.toList(),
                    key = { it }
                ) { catalogKey ->
                    val isDisabled = catalogKey in disabledCatalogs.value
                    val displayName = config.customCatalogTitles[catalogKey]
                        ?: catalogKey.substringAfterLast("|").replace("_", " ")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val index = catalogOrder.indexOf(catalogKey)
                                if (index > 0) {
                                    catalogOrder.removeAt(index)
                                    catalogOrder.add(index - 1, catalogKey)
                                    viewModel.updateTab(
                                        currentTab.copy(
                                            config = config.copy(catalogOrder = catalogOrder.toList())
                                        )
                                    )
                                }
                            },
                            enabled = catalogOrder.indexOf(catalogKey) > 0,
                            modifier = Modifier.weight(0.1f),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = null
                            )
                        }

                        Button(
                            onClick = {
                                val index = catalogOrder.indexOf(catalogKey)
                                if (index < catalogOrder.size - 1) {
                                    catalogOrder.removeAt(index)
                                    catalogOrder.add(index + 1, catalogKey)
                                    viewModel.updateTab(
                                        currentTab.copy(
                                            config = config.copy(catalogOrder = catalogOrder.toList())
                                        )
                                    )
                                }
                            },
                            enabled = catalogOrder.indexOf(catalogKey) < catalogOrder.size - 1,
                            modifier = Modifier.weight(0.1f),
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = null
                            )
                        }

                        Button(
                            onClick = {
                                val current = disabledCatalogs.value.toMutableSet()
                                if (isDisabled) {
                                    current.remove(catalogKey)
                                } else {
                                    current.add(catalogKey)
                                }
                                disabledCatalogs.value = current
                                viewModel.updateTab(
                                    currentTab.copy(
                                        config = config.copy(disabledCatalogs = current.toList())
                                    )
                                )
                            },
                            modifier = Modifier.weight(0.8f),
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
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isDisabled) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary
                                )
                                Switch(
                                    checked = !isDisabled,
                                    onCheckedChange = { enabled ->
                                        val current = disabledCatalogs.value.toMutableSet()
                                        if (!enabled) {
                                            current.add(catalogKey)
                                        } else {
                                            current.remove(catalogKey)
                                        }
                                        disabledCatalogs.value = current
                                        viewModel.updateTab(
                                            currentTab.copy(
                                                config = config.copy(disabledCatalogs = current.toList())
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
}
