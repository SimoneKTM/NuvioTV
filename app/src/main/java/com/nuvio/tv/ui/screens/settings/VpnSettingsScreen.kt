@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

@Composable
fun VpnSettingsScreen(
    viewModel: VpnSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "wireguard.conf"
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
            }
        }.getOrNull()
        if (text != null) viewModel.importConfig(name, text)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.permissionIntent) {
        val intent = uiState.permissionIntent ?: return@LaunchedEffect
        permissionLauncher.launch(intent)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.vpn_title),
            subtitle = stringResource(R.string.vpn_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "vpn_config") {
                        SettingsActionRow(
                            title = stringResource(R.string.vpn_config_title),
                            subtitle = stringResource(R.string.vpn_config_subtitle),
                            value = uiState.configName
                                ?: stringResource(R.string.vpn_config_missing),
                            onClick = {
                                pickerLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.then(
                                if (initialFocusRequester != null) {
                                    Modifier.focusRequester(initialFocusRequester)
                                } else {
                                    Modifier
                                }
                            )
                        )
                    }

                    item(key = "vpn_status") {
                        SettingsActionRow(
                            title = stringResource(R.string.vpn_status_title),
                            subtitle = stringResource(R.string.vpn_status_subtitle),
                            value = stringResource(
                                if (uiState.isConnected) R.string.vpn_status_connected
                                else R.string.vpn_status_disconnected
                            ),
                            onClick = {},
                            enabled = uiState.hasConfig
                        )
                    }

                    item(key = "vpn_enable") {
                        SettingsToggleRow(
                            title = stringResource(R.string.vpn_enable_title),
                            subtitle = stringResource(R.string.vpn_enable_subtitle),
                            checked = uiState.isConnected,
                            enabled = uiState.hasConfig && !uiState.isBusy,
                            onToggle = {
                                if (uiState.isConnected) {
                                    viewModel.disconnect()
                                } else {
                                    viewModel.connect()
                                }
                            }
                        )
                    }

                    if (uiState.hasConfig) {
                        item(key = "vpn_remove_config") {
                            SettingsActionRow(
                                title = stringResource(R.string.vpn_remove_config_title),
                                subtitle = stringResource(R.string.vpn_remove_config_subtitle),
                                onClick = { viewModel.removeConfig() },
                                enabled = !uiState.isBusy
                            )
                        }
                    }

                    val message = uiState.message
                    if (message != null) {
                        item(key = "vpn_message") {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.Warning
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
}