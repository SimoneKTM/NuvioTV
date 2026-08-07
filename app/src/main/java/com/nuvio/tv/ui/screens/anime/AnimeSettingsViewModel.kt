package com.nuvio.tv.ui.screens.anime

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnimeSettingsUiState(
    val addons: List<Addon> = emptyList(),
    val isLoading: Boolean = true,
    val installUrl: String = "",
    val isInstalling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AnimeSettingsViewModel @Inject constructor(
    private val animeAddonRepository: AnimeAddonRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeSettingsUiState())
    val uiState: StateFlow<AnimeSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            animeAddonRepository.getInstalledAnimeAddons()
                .distinctUntilChanged()
                .collectLatest { addons ->
                    _uiState.update {
                        it.copy(addons = addons, isLoading = false)
                    }
                }
        }
    }

    fun onInstallUrlChange(value: String) {
        _uiState.update { it.copy(installUrl = value, error = null) }
    }

    fun installAddon() {
        val url = _uiState.value.installUrl.trim()
        if (url.isEmpty()) return
        _uiState.update { it.copy(isInstalling = true, error = null) }
        viewModelScope.launch {
            when (val result = animeAddonRepository.fetchAnimeAddon(url)) {
                is NetworkResult.Success -> {
                    animeAddonRepository.addAnimeAddon(url)
                    _uiState.update { it.copy(isInstalling = false, installUrl = "") }
                    Log.d("AnimeSettingsViewModel", "Installed anime addon url=$url")
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            error = result.message ?: "Failed to install addon (code ${result.code})"
                        )
                    }
                }
                NetworkResult.Loading -> { }
            }
        }
    }

    fun removeAddon(url: String) {
        viewModelScope.launch {
            animeAddonRepository.removeAnimeAddon(url)
        }
    }

    fun moveAddonUp(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.addons
            val index = current.indexOfFirst { it.baseUrl == url }
            if (index <= 0) return@launch
            val reordered = current.toMutableList()
            reordered.removeAt(index)
            reordered.add(index - 1, current[index])
            animeAddonRepository.setAnimeAddonOrder(reordered.map { it.baseUrl })
        }
    }

    fun moveAddonDown(url: String) {
        viewModelScope.launch {
            val current = _uiState.value.addons
            val index = current.indexOfFirst { it.baseUrl == url }
            if (index == -1 || index >= current.lastIndex) return@launch
            val reordered = current.toMutableList()
            reordered.removeAt(index)
            reordered.add(index + 1, current[index])
            animeAddonRepository.setAnimeAddonOrder(reordered.map { it.baseUrl })
        }
    }

    fun setAddonEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            animeAddonRepository.setAnimeAddonEnabled(url, enabled)
        }
    }
}
