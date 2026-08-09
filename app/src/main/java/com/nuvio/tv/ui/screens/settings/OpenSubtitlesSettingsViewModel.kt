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

data class OpenSubtitlesSettingsUiState(
    val enabledDirect: Boolean = false,
    val hasApiKey: Boolean = false,
    val hasUserCredentials: Boolean = false,
    val username: String = "",
    val languages: Set<String> = emptySet()
)

@HiltViewModel
class OpenSubtitlesSettingsViewModel @Inject constructor(
    private val directDataStore: OpenSubtitlesDirectDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpenSubtitlesSettingsUiState())
    val uiState: StateFlow<OpenSubtitlesSettingsUiState> = _uiState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenSubtitlesSettingsUiState())

    init {
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
}