package com.nuvio.tv.ui.screens.anime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AddonConfigServer
import com.nuvio.tv.core.server.AddonInfo
import com.nuvio.tv.core.server.AddonWebConfigMode
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.server.PageState
import com.nuvio.tv.core.server.PendingAddonChange
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.ui.screens.addon.PendingChangeInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AnimeSettingsUiState(
    val addons: List<Addon> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val installUrl: String = "",
    val isInstalling: Boolean = false,
    val error: String? = null,
    // QR mode
    val isQrModeActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    // Pending change from phone
    val pendingChange: PendingChangeInfo? = null
)

@HiltViewModel
class AnimeSettingsViewModel @Inject constructor(
    private val animeAddonRepository: AnimeAddonRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeSettingsUiState())
    val uiState: StateFlow<AnimeSettingsUiState> = _uiState.asStateFlow()

    private var server: AddonConfigServer? = null
    private var logoBytes: ByteArray? = null

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
        loadLogoBytes()
    }

    private fun loadLogoBytes() {
        try {
            val inputStream = context.resources.openRawResource(R.drawable.app_logo_wordmark)
            logoBytes = inputStream.use { it.readBytes() }
        } catch (_: Exception) { }
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

    fun refreshAddons() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            try {
                animeAddonRepository.refreshAnimeAddons()
            } catch (e: Exception) {
                Log.e("AnimeSettingsViewModel", "Failed to refresh anime addons", e)
                _uiState.update { it.copy(error = context.getString(R.string.anime_settings_refresh_failed)) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun startQrMode() {
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _uiState.update { it.copy(error = context.getString(R.string.error_network_required)) }
            return
        }

        stopServerInternal()

        server = AddonConfigServer.startOnAvailablePort(
            context = context,
            webConfigMode = AddonWebConfigMode.ANIME_ADDONS,
            currentPageStateProvider = { buildPageState() },
            onChangeProposed = { change -> handleChangeProposed(change) },
            logoProvider = { logoBytes }
        )

        val activeServer = server
        if (activeServer == null) {
            _uiState.update { it.copy(error = context.getString(R.string.error_server_ports_unavailable)) }
            return
        }

        val url = "http://$ip:${activeServer.listeningPort}"
        val qrBitmap = QrCodeGenerator.generate(url, 512)

        _uiState.update {
            it.copy(
                isQrModeActive = true,
                qrCodeBitmap = qrBitmap,
                serverUrl = url,
                error = null
            )
        }
    }

    fun stopQrMode() {
        stopServerInternal()
        _uiState.update {
            it.copy(
                isQrModeActive = false,
                qrCodeBitmap = null,
                serverUrl = null,
                pendingChange = null
            )
        }
    }

    private fun buildPageState(): PageState {
        val addons = _uiState.value.addons.map { addon ->
            AddonInfo(
                url = addon.baseUrl,
                name = addon.displayName.ifBlank { addon.baseUrl },
                description = addon.description
            )
        }
        return PageState(
            addons = addons,
            catalogs = emptyList()
        )
    }

    private fun handleChangeProposed(change: PendingAddonChange) {
        val currentUrls = _uiState.value.addons.map { it.baseUrl }
        val proposedNormalized = change.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()
        val currentNormalized = currentUrls.map { normalizeUrlForComparison(it) }.toSet()

        val added = change.proposedUrls.filter { normalizeUrlForComparison(it) !in currentNormalized }
        val removed = currentUrls.filter { normalizeUrlForComparison(it) !in proposedNormalized }

        val currentNameMap = _uiState.value.addons.associateBy(
            { normalizeUrlForComparison(it.baseUrl) },
            { it.displayName }
        )
        val removedNames = removed.associateWith { url ->
            currentNameMap[normalizeUrlForComparison(url)] ?: url
        }

        _uiState.update {
            it.copy(
                pendingChange = PendingChangeInfo(
                    changeId = change.id,
                    proposedUrls = change.proposedUrls,
                    addedUrls = added,
                    removedUrls = removed,
                    removedNames = removedNames,
                    proposedCatalogOrderKeys = emptyList()
                )
            )
        }

        if (added.isNotEmpty()) {
            viewModelScope.launch {
                val addedNames = withContext(Dispatchers.IO) {
                    added.associateWith { url ->
                        fetchAnimeAddonName(url)
                    }
                }
                _uiState.update { state ->
                    val pending = state.pendingChange
                    if (pending == null || pending.changeId != change.id) {
                        state
                    } else {
                        state.copy(
                            pendingChange = pending.copy(addedNames = addedNames)
                        )
                    }
                }
            }
        }
    }

    private suspend fun fetchAnimeAddonName(url: String): String {
        return try {
            when (val result = animeAddonRepository.fetchAnimeAddon(url)) {
                is NetworkResult.Success -> result.data.displayName.ifBlank { url }
                else -> url
            }
        } catch (_: Exception) {
            url
        }
    }

    fun confirmPendingChange() {
        val pending = _uiState.value.pendingChange ?: return

        _uiState.update { it.copy(pendingChange = pending.copy(isApplying = true)) }

        viewModelScope.launch {
            val currentUrls = _uiState.value.addons.map { it.baseUrl }
            val proposedNormalized = pending.proposedUrls.map { normalizeUrlForComparison(it) }.toSet()

            currentUrls
                .filter { normalizeUrlForComparison(it) !in proposedNormalized }
                .forEach { animeAddonRepository.removeAnimeAddon(it) }
            pending.proposedUrls
                .filter { url -> currentUrls.none { normalizeUrlForComparison(it) == normalizeUrlForComparison(url) } }
                .forEach { animeAddonRepository.addAnimeAddon(it) }
            animeAddonRepository.setAnimeAddonOrder(pending.proposedUrls)

            server?.confirmChange(pending.changeId)

            _uiState.update { it.copy(pendingChange = null) }

            delay(2500)

            stopServerInternal()
            _uiState.update {
                it.copy(
                    isQrModeActive = false,
                    qrCodeBitmap = null,
                    serverUrl = null
                )
            }
        }
    }

    fun rejectPendingChange() {
        val pending = _uiState.value.pendingChange ?: return
        server?.rejectChange(pending.changeId)
        _uiState.update { it.copy(pendingChange = null) }
    }

    private fun normalizeUrlForComparison(url: String): String =
        url.trim().trimEnd('/').lowercase()

    private fun stopServerInternal() {
        server?.stop()
        server = null
    }

    override fun onCleared() {
        stopServerInternal()
        super.onCleared()
    }
}