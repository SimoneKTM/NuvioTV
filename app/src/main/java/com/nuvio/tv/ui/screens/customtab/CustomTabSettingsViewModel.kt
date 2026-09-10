package com.nuvio.tv.ui.screens.customtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.MAX_CUSTOM_TABS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class CustomTabSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(CustomTabSettingsUiState())
    val uiState = _uiState

    init {
        loadTabs()
    }

    private fun loadTabs() {
        viewModelScope.launch {
            layoutPreferenceDataStore.customTabs.collect { tabs ->
                _uiState.update { it.copy(tabs = tabs) }
            }
        }
    }

    fun addTab(displayName: String) {
        viewModelScope.launch {
            val currentTabs = layoutPreferenceDataStore.customTabs.first()
            if (currentTabs.size >= MAX_CUSTOM_TABS) return@launch

            val newId = "custom_tab_${System.currentTimeMillis()}"
            val newTab = CustomTab(
                id = newId,
                displayName = displayName
            )
            layoutPreferenceDataStore.addCustomTab(newTab)
        }
    }

    fun updateTab(tab: CustomTab) {
        viewModelScope.launch {
            layoutPreferenceDataStore.updateCustomTab(tab)
        }
    }

    fun removeTab(tabId: String) {
        viewModelScope.launch {
            layoutPreferenceDataStore.removeCustomTab(tabId)
        }
    }

    fun reorderTabs(tabs: List<CustomTab>) {
        viewModelScope.launch {
            layoutPreferenceDataStore.reorderCustomTabs(tabs)
        }
    }
}

data class CustomTabSettingsUiState(
    val tabs: List<CustomTab> = emptyList()
)