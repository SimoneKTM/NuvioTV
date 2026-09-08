package com.nuvio.tv.ui.screens.customtab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.CustomTabIcon
import com.nuvio.tv.domain.model.CustomTabSource
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class CustomTabEditViewModel @Inject constructor(
    @Named("custom_tab_layout") private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val animeAddonRepository: AnimeAddonRepository
) : ViewModel() {

    private var tabId: String = ""

    private val _uiState = MutableStateFlow(CustomTabEditUiState())
    val uiState: StateFlow<CustomTabEditUiState> = _uiState.asStateFlow()

    fun setTabId(id: String) {
        tabId = id
        loadTab()
    }

    private fun loadTab() {
        viewModelScope.launch {
            val tabs = layoutPreferenceDataStore.customTabs.first()
            val tab = tabs.find { it.id == tabId }
            if (tab != null) {
                _uiState.update { it.copy(
                    tab = tab,
                    editedName = tab.name,
                    editedIcon = CustomTabIcon.valueOf(tab.icon)
                ) }
            }
        }
        observeAddons()
    }

    private fun observeAddons() {
        viewModelScope.launch {
            combine(
                animeAddonRepository.addons,
                _uiState.map { it.tab }
            ) { addons, tab ->
                addons to tab
            }
                .distinctUntilChanged()
                .collect { (addons, tab) ->
                    _uiState.update { it.copy(availableAddons = addons.filter { it.isAnimeAddon }) }
                }
        }
    }

    fun updateName(name: String) {
        _uiState.update { it.copy(editedName = name) }
    }

    fun updateIcon(icon: CustomTabIcon) {
        _uiState.update { it.copy(editedIcon = icon) }
    }

    fun toggleSource(source: CustomTabSource, isSelected: Boolean) {
        _uiState.update { state ->
            val currentSources = state.tab?.sources?.toMutableList() ?: mutableListOf()
            if (isSelected) {
                if (currentSources.none { s ->
                    s.addonId == source.addonId &&
                    s.type == source.type &&
                    s.catalogId == source.catalogId
                }) {
                    currentSources.add(source)
                }
            } else {
                currentSources.removeAll { s ->
                    s.addonId == source.addonId &&
                    s.type == source.type &&
                    s.catalogId == source.catalogId
                }
            }
            state.copy(tab = state.tab?.copy(sources = currentSources))
        }
    }

    fun saveChanges() {
        val state = _uiState.value
        state.tab?.let { tab ->
            val updatedTab = tab.copy(
                name = state.editedName.trim(),
                icon = state.editedIcon.name,
                sources = state.tab.sources
            )
            viewModelScope.launch {
                layoutPreferenceDataStore.updateCustomTab(updatedTab)
            }
        }
    }
}

data class CustomTabEditUiState(
    val tab: CustomTab? = null,
    val editedName: String = "",
    val editedIcon: CustomTabIcon = CustomTabIcon.DEFAULT,
    val availableAddons: List<Addon> = emptyList()
)