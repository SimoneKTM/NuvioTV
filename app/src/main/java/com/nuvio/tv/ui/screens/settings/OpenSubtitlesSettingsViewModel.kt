package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenSubtitlesSettingsUiState(
    val isInstalled: Boolean = false,
    val isEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val toggleError: String? = null
)

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val addonRepository: AddonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpenSubtitlesSettingsUiState())
    val uiState: StateFlow<OpenSubtitlesSettingsUiState> = _uiState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenSubtitlesSettingsUiState())

    init {
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { addons ->
                val addon = addons.firstOrNull { normalizeUrl(it.baseUrl) == OPEN_SUBTITLES_URL }
                _uiState.update {
                    it.copy(
                        isInstalled = addon != null,
                        isEnabled = addon?.enabled ?: false,
                        toggleError = null
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, toggleError = null) }
            val installed = _uiState.value.isInstalled
            try {
                if (!installed) {
                    addonRepository.addAddon(OPEN_SUBTITLES_URL)
                }
                addonRepository.setAddonEnabled(OPEN_SUBTITLES_URL, enabled)
            } catch (e: Exception) {
                _uiState.update { it.copy(toggleError = e.message, isBusy = false) }
            }
        }
    }

    fun reinstallAddon() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, toggleError = null) }
            try {
                addonRepository.addAddon(OPEN_SUBTITLES_URL)
            } catch (e: Exception) {
                _uiState.update { it.copy(toggleError = e.message, isBusy = false) }
            }
        }
    }

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/').lowercase()

    companion object {
        private const val OPEN_SUBTITLES_URL = "https://opensubtitles-v3.strem.io"
    }
}