package com.nuvio.tv.ui.screens.customtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CustomTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class CustomTabSettingsViewModel @Inject constructor(
    @Named("custom_tab_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {

    var showAddTabDialog by mutableStateOf(false)
        private set

    private val _uiState = MutableStateFlow(CustomTabSettingsUiState())
    val uiState: StateFlow<CustomTabSettingsUiState> = _uiState.asStateFlow()

    init {
        observeCustomTabs()
    }

    private fun observeCustomTabs() {
        viewModelScope.launch {
            layoutPreferenceDataStore.customTabs
                .distinctUntilChanged()
                .collect { tabs ->
                    _uiState.update { it.copy(tabs = tabs) }
                }
        }
    }

    fun addTab(name: String, icon: CustomTabIcon) {
        val newTab = CustomTab(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            icon = icon.name,
            order = _uiState.value.tabs.size
        )
        viewModelScope.launch {
            layoutPreferenceDataStore.addCustomTab(newTab)
        }
    }

    fun deleteTab(tabId: String) {
        viewModelScope.launch {
            layoutPreferenceDataStore.removeCustomTab(tabId)
        }
    }

    fun moveTab(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            layoutPreferenceDataStore.reorderCustomTabs(fromIndex, toIndex)
        }
    }
}

data class CustomTabSettingsUiState(
    val tabs: List<CustomTab> = emptyList()
)