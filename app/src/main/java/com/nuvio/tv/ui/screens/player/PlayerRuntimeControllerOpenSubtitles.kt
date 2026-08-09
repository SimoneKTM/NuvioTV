package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.OpenSubtitlesManualSubtitle
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val OpenSubtitlesTag = "PlayerOpenSubtitles"

private fun PlayerRuntimeController.currentOpenSubtitlesSearchKey(): String =
    buildString {
        append(contentType ?: "movie")
        append('|')
        append(contentId ?: "")
        append('|')
        append(currentVideoId ?: "")
    }

/**
 * Manual OpenSubtitles search (direct API): searches all results for the
 * current media on demand, then downloads the selected file lazily before
 * attaching it through the standard addon-subtitle pipeline.
 */
internal fun PlayerRuntimeController.observeOpenSubtitlesConfig() {
    scope.launch {
        _uiState.update {
            it.copy(isOpenSubtitlesConfigured = subtitleRepository.isOpenSubtitlesConfigured())
        }
    }
}

internal fun PlayerRuntimeController.openOpenSubtitlesSearch() {
    val state = _uiState.value
    if (!state.isOpenSubtitlesConfigured) return
    if (state.isSearchingOpenSubtitles || state.isDownloadingOpenSubtitles) return
    _uiState.update {
        it.copy(
            showOpenSubtitlesDialog = true,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showMoreDialog = false,
            showControls = true
        )
    }
    val searchKey = currentOpenSubtitlesSearchKey()
    val hasCachedResults =
        state.openSubtitlesSearchKey == searchKey && state.openSubtitlesResults.isNotEmpty()
    if (hasCachedResults) return
    scope.launch {
        _uiState.update { it.copy(isSearchingOpenSubtitles = true, openSubtitlesError = null) }
        val results = try {
            subtitleRepository.searchOpenSubtitles(
                type = contentType ?: "movie",
                id = contentId ?: "",
                videoId = currentVideoId
            )
        } catch (e: Exception) {
            Log.e(OpenSubtitlesTag, "OpenSubtitles manual search failed", e)
            emptyList()
        }
        _uiState.update {
            it.copy(
                isSearchingOpenSubtitles = false,
                openSubtitlesSearchKey = searchKey,
                openSubtitlesResults = results,
                openSubtitlesError = if (results.isEmpty()) {
                    context.getString(R.string.player_opensubtitles_search_empty)
                } else {
                    null
                }
            )
        }
    }
}

internal fun PlayerRuntimeController.dismissOpenSubtitlesDialog() {
    _uiState.update {
        it.copy(
            showOpenSubtitlesDialog = false,
            openSubtitlesError = null
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectOpenSubtitlesResult(item: OpenSubtitlesManualSubtitle) {
    if (_uiState.value.isDownloadingOpenSubtitles) return
    val dialogWasOpen = _uiState.value.showOpenSubtitlesDialog
    _uiState.update { it.copy(isDownloadingOpenSubtitles = true, openSubtitlesError = null) }
    scope.launch {
        val result = try {
            subtitleRepository.downloadOpenSubtitles(item)
        } catch (e: Exception) {
            Log.e(OpenSubtitlesTag, "OpenSubtitles manual download failed", e)
            Result.failure(e)
        }
        val subtitle = result.getOrNull()
        if (subtitle != null) {
            _uiState.update {
                it.copy(
                    isDownloadingOpenSubtitles = false,
                    showOpenSubtitlesDialog = false,
                    showSubtitleOverlay = dialogWasOpen,
                    showControls = true
                )
            }
            autoSubtitleSelected = true
            rememberAddonSubtitleSelection(subtitle)
            selectAddonSubtitle(subtitle)
        } else {
            val failureMessage = result.exceptionOrNull()?.message.orEmpty()
            val errorMessage = when {
                failureMessage == "missing_api_key" ->
                    context.getString(R.string.player_opensubtitles_error_missing_api_key)
                failureMessage == "no_credentials" || failureMessage.startsWith("login error") ->
                    context.getString(R.string.player_opensubtitles_error_credentials)
                else -> context.getString(R.string.player_opensubtitles_error_download)
            }
            _uiState.update {
                it.copy(
                    isDownloadingOpenSubtitles = false,
                    openSubtitlesError = errorMessage
                )
            }
        }
    }
}
