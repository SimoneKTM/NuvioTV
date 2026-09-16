package com.nuvio.tv.ui.screens.extra

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class ExtraHubUiState(
    val extraTabVisible: Boolean = true,
    val extraTabLogoIndex: Int = 0
)

sealed class ExtraHubEvent {
    data class SetExtraTabVisible(val visible: Boolean) : ExtraHubEvent()
    data class SetExtraTabLogoIndex(val index: Int) : ExtraHubEvent()
}

@HiltViewModel
class ExtraHubViewModel @Inject constructor(
    @Named("extra_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExtraHubUiState())
    val uiState: StateFlow<ExtraHubUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            layoutPreferenceDataStore.extraTabVisible
                .distinctUntilChanged()
                .collectLatest { visible ->
                    _uiState.update { state ->
                        if (state.extraTabVisible == visible) state else state.copy(extraTabVisible = visible)
                    }
                }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.extraTabLogoIndex
                .distinctUntilChanged()
                .collectLatest { index ->
                    _uiState.update { state ->
                        if (state.extraTabLogoIndex == index) state else state.copy(extraTabLogoIndex = index)
                    }
                }
        }
    }

    fun onEvent(event: ExtraHubEvent) {
        when (event) {
            is ExtraHubEvent.SetExtraTabVisible -> setExtraTabVisible(event.visible)
            is ExtraHubEvent.SetExtraTabLogoIndex -> setExtraTabLogoIndex(event.index)
        }
    }

    private fun setExtraTabVisible(visible: Boolean) {
        if (_uiState.value.extraTabVisible == visible) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setExtraTabVisible(visible)
        }
    }

    private fun setExtraTabLogoIndex(index: Int) {
        if (_uiState.value.extraTabLogoIndex == index) return
        viewModelScope.launch {
            layoutPreferenceDataStore.setExtraTabLogoIndex(index)
        }
    }
}
