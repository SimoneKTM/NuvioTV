package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.OpenSubtitlesDirectDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val OPEN_SUBTITLES_URL = "https://opensubtitles-v3.strem.io"

data class OpenSubtitlesSettingsUiState(
    val isInstalled: Boolean = false,
    val isEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val toggleError: String? = null,
    val addonUrl: String = OPEN_SUBTITLES_URL,
    val enabledDirect: Boolean = false,
    val hasApiKey: Boolean = false,
    val hasUserCredentials: Boolean = false,
    val username: String = "",
    val languages: Set<String> = emptySet()
)

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val addonRepository: com.nuvio.tv.domain.repository.AddonRepository,
    private val directDataStore: OpenSubtitlesDirectDataStore
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
        viewModelScope.launch {
            val settings = directDataStore.settings.first()
            _uiState.update {
                it.copy(
                    enabledDirect = settings.enabled,
                    hasApiKey = settings.hasApiKey,
                    hasUserCredentials = settings.hasUserCredentials,
                    username = settings.username,
                    languages = settings.languages
                )
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

    fun setDirectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            directDataStore.setEnabled(enabled)
            _uiState.update { it.copy(enabledDirect = enabled) }
        }
    }

    fun setApiKey(value: String) {
        viewModelScope.launch {
            directDataStore.setApiKey(value)
            _uiState.update { it.copy(hasApiKey = value.trim().isNotBlank()) }
        }
    }

    fun setUsername(value: String) {
        viewModelScope.launch {
            directDataStore.setUsername(value)
            _uiState.update { it.copy(username = value.trim()) }
        }
    }

    fun setPassword(value: String) {
        viewModelScope.launch {
            directDataStore.setPassword(value)
        }
    }

    fun toggleLanguage(code: String, selected: Boolean) {
        viewModelScope.launch {
            val current = directDataStore.settings.first().languages
            val updated = if (selected) current + code else current - code
            directDataStore.setLanguages(updated)
            _uiState.update { it.copy(languages = updated) }
        }
    }

    fun clearUserToken() {
        viewModelScope.launch {
            directDataStore.setUserToken("")
        }
    }

    private fun normalizeUrl(url: String): String =
        url.trim().trimEnd('/').lowercase()
}