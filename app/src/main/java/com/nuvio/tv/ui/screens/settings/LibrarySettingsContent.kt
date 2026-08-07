@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun LibrarySettingsContent(
    trackingViewModel: TrackingSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester?
) {
    val trackingState by trackingViewModel.uiState.collectAsStateWithLifecycle()
    val libraryFocusRequester = remember { FocusRequester() }
    val watchProgressFocusRequester = remember { FocusRequester() }
    var showLibrarySourceDialog by remember { mutableStateOf(false) }
    var showWatchProgressDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_library_title),
            subtitle = stringResource(R.string.settings_library_description)
        )
        SettingsGroupCard(
            title = stringResource(R.string.tracking_sources_title),
            subtitle = stringResource(R.string.tracking_sources_subtitle),
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsActionRow(
                title = stringResource(R.string.trakt_library_source_title),
                subtitle = stringResource(R.string.trakt_library_source_subtitle),
                value = librarySourceLabel(trackingState.librarySourceMode),
                enabled = trackingState.isReady,
                onClick = { showLibrarySourceDialog = true },
                modifier = Modifier
                    .focusRequester(initialFocusRequester ?: libraryFocusRequester)
                    .testTag(TrackingSettingsTestTags.LIBRARY_SOURCE)
            )
            SettingsActionRow(
                title = stringResource(R.string.trakt_watch_progress_title),
                subtitle = stringResource(R.string.trakt_watch_progress_subtitle),
                value = watchProgressSourceLabel(trackingState.watchProgressSource),
                enabled = trackingState.isReady,
                onClick = { showWatchProgressDialog = true },
                modifier = Modifier
                    .focusRequester(watchProgressFocusRequester)
                    .testTag(TrackingSettingsTestTags.WATCH_PROGRESS_SOURCE)
            )
        }
    }

    if (showLibrarySourceDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_library_source_dialog_title),
            subtitle = stringResource(R.string.tracking_library_source_dialog_subtitle),
            options = trackingState.availableLibrarySourceModes.map { mode ->
                SettingsPickerOption(mode, librarySourceLabel(mode))
            },
            selectedValue = trackingState.librarySourceMode,
            onOptionSelected = { mode ->
                trackingViewModel.selectLibrarySourceMode(mode)
                showLibrarySourceDialog = false
            },
            onDismiss = { showLibrarySourceDialog = false },
            width = 620.dp,
            maxHeight = 340.dp
        )
    }

    if (showWatchProgressDialog) {
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.trakt_watch_progress_dialog_title),
            subtitle = stringResource(R.string.tracking_watch_progress_dialog_subtitle),
            options = trackingState.availableWatchProgressSources.map { source ->
                SettingsPickerOption(source, watchProgressSourceLabel(source))
            },
            selectedValue = trackingState.watchProgressSource,
            onOptionSelected = { source ->
                trackingViewModel.selectWatchProgressSource(source)
                showWatchProgressDialog = false
            },
            onDismiss = { showWatchProgressDialog = false },
            width = 660.dp,
            maxHeight = 360.dp
        )
    }
}

@Composable
private fun watchProgressSourceLabel(source: WatchProgressSource): String = when (source) {
    WatchProgressSource.TRAKT -> stringResource(R.string.trakt_name)
    WatchProgressSource.SIMKL -> stringResource(R.string.simkl_name)
    WatchProgressSource.ANILIST -> stringResource(R.string.anilist_name)
    WatchProgressSource.KITSU -> stringResource(R.string.kitsu_name)
    WatchProgressSource.MAL -> stringResource(R.string.mal_name)
    WatchProgressSource.NUVIO_SYNC -> stringResource(R.string.trakt_watch_progress_source_nuvio)
}

@Composable
private fun librarySourceLabel(mode: LibrarySourceMode): String = when (mode) {
    LibrarySourceMode.TRAKT -> stringResource(R.string.trakt_name)
    LibrarySourceMode.SIMKL -> stringResource(R.string.simkl_name)
    LibrarySourceMode.ANILIST -> stringResource(R.string.anilist_name)
    LibrarySourceMode.KITSU -> stringResource(R.string.kitsu_name)
    LibrarySourceMode.MAL -> stringResource(R.string.mal_name)
    LibrarySourceMode.LOCAL -> stringResource(R.string.trakt_library_source_nuvio)
}
