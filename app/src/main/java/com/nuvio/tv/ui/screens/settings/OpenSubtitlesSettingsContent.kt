package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun OpenSubtitlesSettingsContent(
    viewModel: OpenSubtitlesSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.toggleError) {
        val error = uiState.toggleError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.opensubtitles_title),
            subtitle = stringResource(R.string.settings_opensubtitles_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val state = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "opensubtitles_enable") {
                        SettingsToggleRow(
                            title = stringResource(R.string.opensubtitles_enable_title),
                            subtitle = stringResource(R.string.opensubtitles_enable_subtitle),
                            checked = uiState.isInstalled && uiState.isEnabled,
                            onToggle = { viewModel.setEnabled(!uiState.isEnabled) },
                            modifier = Modifier
                                .then(
                                    if (initialFocusRequester != null) {
                                        Modifier.focusRequester(initialFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                ),
                            enabled = uiState.isInstalled && !uiState.isBusy
                        )
                    }

                    if (uiState.isInstalled) {
                        item(key = "opensubtitles_url") {
                            SettingsActionRow(
                                title = stringResource(R.string.opensubtitles_url_title),
                                subtitle = stringResource(R.string.opensubtitles_url_subtitle),
                                value = "https://opensubtitles-v3.strem.io",
                                onClick = {},
                                enabled = false
                            )
                        }
                    } else {
                        item(key = "opensubtitles_not_installed") {
                            SettingsActionRow(
                                title = stringResource(R.string.opensubtitles_not_installed_title),
                                subtitle = stringResource(R.string.opensubtitles_not_installed_subtitle),
                                onClick = {},
                                enabled = false
                            )
                        }
                        item(key = "opensubtitles_reinstall") {
                            SettingsActionRow(
                                title = stringResource(R.string.opensubtitles_reinstall_title),
                                subtitle = stringResource(R.string.opensubtitles_reinstall_subtitle),
                                onClick = { viewModel.reinstallAddon() },
                                enabled = !uiState.isBusy
                            )
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = state)
            }
        }
    }
}