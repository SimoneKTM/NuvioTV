package com.nuvio.tv.ui.screens.customtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.MAX_CUSTOM_TABS
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CustomTabSettingsViewModel @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val addonRepository: AddonRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CustomTabSettingsUiState())
    val uiState = _uiState

    init {
        loadTabs()
        loadAvailableAddons()
    }

    private fun loadTabs() {
        viewModelScope.launch {
            layoutPreferenceDataStore.customTabs.collect { tabs ->
                _uiState.update { it.copy(tabs = tabs) }
            }
        }
    }

    private fun loadAvailableAddons() {
        viewModelScope.launch {
            addonRepository.getInstalledAddons().collect { addons ->
                _uiState.update { it.copy(availableAddons = addons) }
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
    val tabs: List<CustomTab> = emptyList(),
    val availableAddons: List<Addon> = emptyList()
)