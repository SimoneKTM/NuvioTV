package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.ExtraTvdbSettingsDataStore
import com.nuvio.tv.domain.model.TvdbSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExtraTvdbSettingsViewModel @Inject constructor(
    private val dataStore: ExtraTvdbSettingsDataStore
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
