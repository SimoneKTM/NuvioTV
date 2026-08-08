package com.nuvio.tv.core.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class WireGuardVpnController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state: StateFlow<Tunnel.State> = _state.asStateFlow()

    private val tunnelName = "NuvioVPN"

    private val backend: GoBackend by lazy(LazyThreadSafetyMode.NONE) {
        GoBackend(context)
    }

    private val tunnel: Tunnel = object : Tunnel {
        override fun getName(): String = tunnelName

        override fun onStateChange(newState: Tunnel.State) {
            _state.value = newState
        }
    }

    fun isAuthorized(): Boolean = GoBackend.VpnService.prepare(context) == null

    fun permissionIntent(): android.content.Intent? =
        GoBackend.VpnService.prepare(context)

    suspend fun start(configText: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val config = withTimeout(10_000) {
                Config.parse(BufferedReader(StringReader(configText)))
            }
            backend.setState(tunnel, Tunnel.State.UP, config)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}