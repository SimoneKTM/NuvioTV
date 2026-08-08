package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import com.nuvio.tv.R
import com.nuvio.tv.core.vpn.WireGuardVpnController
import com.nuvio.tv.data.local.VpnSettingsDataStore
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

data class VpnSettingsUiState(
    val configName: String? = null,
    val hasConfig: Boolean = false,
    val isConnected: Boolean = false,
    val isBusy: Boolean = false,
    val needsPermission: Boolean = false,
    val permissionIntent: Intent? = null,
    val message: String? = null
)

@HiltViewModel
class VpnSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vpnDataStore: VpnSettingsDataStore,
    private val vpnController: WireGuardVpnController
) : ViewModel() {

    private val localState = MutableStateFlow(
        VpnSettingsUiState(
            hasConfig = false,
            isConnected = false,
            isBusy = false,
            needsPermission = false,
            message = null
        )
    )

    val uiState: StateFlow<VpnSettingsUiState> = combine(
        vpnDataStore.configName,
        vpnController.state,
        localState
    ) { name, tunnelState, local ->
        local.copy(
            configName = name,
            hasConfig = name != null,
            isConnected = tunnelState.name == "UP"
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnSettingsUiState())

    fun importConfig(name: String, text: String) {
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, message = null) }
            runCatching { vpnDataStore.saveConfig(name, text) }
                .onFailure { e -> localState.update { it.copy(message = e.message) } }
            localState.update { it.copy(isBusy = false) }
        }
    }

    fun connect() {
        val current = localState.value
        if (current.isBusy || current.configName == null) return
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, message = null) }
            if (!vpnController.isAuthorized()) {
                localState.update {
                    it.copy(
                        isBusy = false,
                        needsPermission = true,
                        permissionIntent = vpnController.permissionIntent()
                    )
                }
                return@launch
            }
            startTunnel()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        localState.update { it.copy(needsPermission = false, permissionIntent = null) }
        if (!granted) {
            localState.update {
                it.copy(message = context.getString(R.string.vpn_permission_denied))
            }
            return
        }
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, message = null) }
            startTunnel()
        }
    }

    fun disconnect() {
        if (localState.value.isBusy) return
        viewModelScope.launch {
            localState.update { it.copy(isBusy = true, message = null) }
            vpnController.stop()
                .onFailure { e -> localState.update { it.copy(message = e.message) } }
            localState.update { it.copy(isBusy = false) }
        }
    }

    fun removeConfig() {
        viewModelScope.launch {
            vpnController.stop()
            vpnDataStore.removeConfig()
        }
    }

    private suspend fun startTunnel() {
        val configText = vpnDataStore.configText.first()
            ?: return
        vpnController.start(configText)
            .onSuccess { localState.update { it.copy(message = null) } }
            .onFailure { e -> localState.update { it.copy(message = e.message) } }
        localState.update { it.copy(isBusy = false) }
    }
}