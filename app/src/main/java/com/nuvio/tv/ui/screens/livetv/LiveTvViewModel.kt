package com.nuvio.tv.ui.screens.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.data.local.LiveTvSettingsDataStore
import com.nuvio.tv.data.repository.LiveTvRepository
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvPlaylist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiveTvPlaylistState(
    val playlist: LiveTvPlaylist,
    val channels: List<LiveTvChannel> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class LiveTvUiState(
    val playlists: List<LiveTvPlaylistState> = emptyList(),
    val isAdding: Boolean = false,
    val addError: String? = null,
    val channels: List<LiveTvChannel> = emptyList()
)

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val dataStore: LiveTvSettingsDataStore,
    private val repository: LiveTvRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val isAdding = MutableStateFlow(false)
    private val addError = MutableStateFlow<String?>(null)

    private val playlistStates = MutableStateFlow<Map<String, LiveTvPlaylistState>>(emptyMap())

    val uiState: StateFlow<LiveTvUiState> = combine(
        dataStore.playlists,
        playlistStates,
        isAdding,
        addError
    ) { saved, states, adding, error ->
        val playlists = saved.map { savedItem ->
            states[savedItem.id] ?: LiveTvPlaylistState(playlist = savedItem)
        }
        LiveTvUiState(
            playlists = playlists,
            isAdding = adding,
            addError = error,
            channels = playlists.flatMap { it.channels }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveTvUiState())

    init {
        refreshAllPlaylists()
    }

    fun addPlaylist(url: String, onAdded: () -> Unit = {}) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            isAdding.value = true
            addError.value = null
            try {
                val playlists = dataStore.playlists.first().toMutableList()
                val existing = playlists.firstOrNull { it.sourceUrl == trimmed }
                val playlist = existing ?: LiveTvPlaylist(
                    id = trimmed,
                    sourceUrl = trimmed,
                    name = LiveTvRepository.displayNameForUrl(trimmed)
                )
                if (existing == null) {
                    playlists += playlist
                    dataStore.setPlaylists(playlists)
                }
                refreshPlaylist(playlist)
                if (existing == null) onAdded()
            } catch (error: Throwable) {
                addError.value = error.message ?: context.getString(R.string.live_tv_error_generic)
            } finally {
                isAdding.value = false
            }
        }
    }

    fun addXtreamPlaylist(serverUrl: String, username: String, password: String, onAdded: () -> Unit = {}) {
        val server = serverUrl.trim()
        if (server.isBlank()) return
        viewModelScope.launch {
            isAdding.value = true
            addError.value = null
            try {
                val playlists = dataStore.playlists.first().toMutableList()
                val existing = playlists.firstOrNull { it.xtreamServerUrl == server }
                val playlist = existing ?: LiveTvPlaylist(
                    id = server,
                    sourceUrl = LiveTvRepository.xtreamPlaylistUrl(server, username, password),
                    name = server.substringAfter("://").substringBefore('/'),
                    xtreamServerUrl = server,
                    xtreamUsername = username.trim(),
                    xtreamPassword = password
                )
                if (existing == null) {
                    playlists += playlist
                    dataStore.setPlaylists(playlists)
                }
                refreshPlaylist(playlist)
                if (existing == null) onAdded()
            } catch (error: Throwable) {
                addError.value = error.message ?: context.getString(R.string.live_tv_error_generic)
            } finally {
                isAdding.value = false
            }
        }
    }

    fun refreshAllPlaylists() {
        viewModelScope.launch {
            val saved = dataStore.playlists.first()
            saved.forEach { refreshPlaylist(it) }
        }
    }

    fun refreshPlaylist(playlist: LiveTvPlaylist) {
        playlistStates.update { current ->
            current + (playlist.id to (current[playlist.id]
                ?: LiveTvPlaylistState(playlist = playlist)).copy(isLoading = true, errorMessage = null))
        }
        viewModelScope.launch {
            val result = repository.fetchPlaylist(playlist)
            playlistStates.update { current ->
                val base = current[playlist.id] ?: LiveTvPlaylistState(playlist = playlist)
                val updated = result.fold(
                    onSuccess = { channels ->
                        base.copy(channels = channels, isLoading = false, errorMessage = null)
                    },
                    onFailure = { error ->
                        base.copy(
                            isLoading = false,
                            errorMessage = error.message ?: context.getString(R.string.live_tv_error_generic)
                        )
                    }
                )
                current + (playlist.id to updated)
            }
        }
    }

    fun removePlaylist(playlist: LiveTvPlaylist) {
        viewModelScope.launch {
            val playlists = dataStore.playlists.first().filterNot { it.id == playlist.id }
            dataStore.setPlaylists(playlists)
            playlistStates.update { current -> current - playlist.id }
        }
    }

    fun clearAddError() {
        addError.value = null
    }
}