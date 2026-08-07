package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.AnimeTvdbSettingsDataStore
import com.nuvio.tv.data.local.TvdbSettingsDataStore
import com.nuvio.tv.domain.model.TvdbSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

interface TvdbSettingsController {
    val uiState: StateFlow<TvdbSettingsUiState>
    fun onEvent(event: TvdbSettingsEvent)
}

@HiltViewModel
class TvdbSettingsViewModel @Inject constructor(
    private val dataStore: TvdbSettingsDataStore
) : ViewModel(), TvdbSettingsController {

    private val _uiState = MutableStateFlow(TvdbSettingsUiState())
    override val uiState: StateFlow<TvdbSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    override fun onEvent(event: TvdbSettingsEvent) {
        when (event) {
            is TvdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TvdbSettingsEvent.SetApiKey -> update { dataStore.setApiKey(event.apiKey) }
            is TvdbSettingsEvent.SetLanguage -> update { dataStore.setLanguage(event.language) }
            is TvdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TvdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TvdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TvdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TvdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TvdbSettingsEvent.ToggleSeasonPosters -> update { dataStore.setUseSeasonPosters(event.enabled) }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

@HiltViewModel
class AnimeTvdbSettingsViewModel @Inject constructor(
    private val dataStore: AnimeTvdbSettingsDataStore
) : ViewModel(), TvdbSettingsController {

    private val _uiState = MutableStateFlow(TvdbSettingsUiState())
    override val uiState: StateFlow<TvdbSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.settings.collectLatest { settings ->
                _uiState.update { it.fromSettings(settings) }
            }
        }
    }

    override fun onEvent(event: TvdbSettingsEvent) {
        when (event) {
            is TvdbSettingsEvent.ToggleEnabled -> update { dataStore.setEnabled(event.enabled) }
            is TvdbSettingsEvent.SetApiKey -> update { dataStore.setApiKey(event.apiKey) }
            is TvdbSettingsEvent.SetLanguage -> update { dataStore.setLanguage(event.language) }
            is TvdbSettingsEvent.ToggleTrailers -> update { dataStore.setUseTrailers(event.enabled) }
            is TvdbSettingsEvent.ToggleArtwork -> update { dataStore.setUseArtwork(event.enabled) }
            is TvdbSettingsEvent.ToggleBasicInfo -> update { dataStore.setUseBasicInfo(event.enabled) }
            is TvdbSettingsEvent.ToggleCredits -> update { dataStore.setUseCredits(event.enabled) }
            is TvdbSettingsEvent.ToggleEpisodes -> update { dataStore.setUseEpisodes(event.enabled) }
            is TvdbSettingsEvent.ToggleSeasonPosters -> update { dataStore.setUseSeasonPosters(event.enabled) }
        }
    }

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch { action() }
    }
}

data class TvdbSettingsUiState(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val language: String = "en",
    val useTrailers: Boolean = true,
    val useArtwork: Boolean = true,
    val useBasicInfo: Boolean = true,
    val useCredits: Boolean = true,
    val useEpisodes: Boolean = true,
    val useSeasonPosters: Boolean = true
) {
    fun fromSettings(settings: TvdbSettings): TvdbSettingsUiState = copy(
        enabled = settings.enabled,
        apiKey = settings.apiKey,
        language = settings.language,
        useTrailers = settings.useTrailers,
        useArtwork = settings.useArtwork,
        useBasicInfo = settings.useBasicInfo,
        useCredits = settings.useCredits,
        useEpisodes = settings.useEpisodes,
        useSeasonPosters = settings.useSeasonPosters
    )
}

sealed class TvdbSettingsEvent {
    data class ToggleEnabled(val enabled: Boolean) : TvdbSettingsEvent()
    data class SetApiKey(val apiKey: String) : TvdbSettingsEvent()
    data class SetLanguage(val language: String) : TvdbSettingsEvent()
    data class ToggleTrailers(val enabled: Boolean) : TvdbSettingsEvent()
    data class ToggleArtwork(val enabled: Boolean) : TvdbSettingsEvent()
    data class ToggleBasicInfo(val enabled: Boolean) : TvdbSettingsEvent()
    data class ToggleCredits(val enabled: Boolean) : TvdbSettingsEvent()
    data class ToggleEpisodes(val enabled: Boolean) : TvdbSettingsEvent()
    data class ToggleSeasonPosters(val enabled: Boolean) : TvdbSettingsEvent()
}
