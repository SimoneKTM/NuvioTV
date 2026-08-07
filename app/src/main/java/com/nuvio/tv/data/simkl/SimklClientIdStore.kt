package com.nuvio.tv.data.simkl

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.SimklSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a user-configured Simkl client id (stored in [SimklSettingsDataStore])
 * onto the shared [SimklApiConfiguration] singleton so that every API call and
 * credential check uses the custom id when set, falling back to BuildConfig.
 */
@Singleton
class SimklClientIdStore @Inject constructor(
    private val configuration: SimklApiConfiguration,
    private val dataStore: SimklSettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob())

    private val _customClientId = MutableStateFlow("")
    val customClientId: StateFlow<String> = _customClientId.asStateFlow()

    init {
        scope.launch {
            try {
                dataStore.customClientId.collectLatest { custom ->
                    _customClientId.value = custom
                    configuration.clientId = custom.ifBlank { BuildConfig.SIMKL_CLIENT_ID }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                configuration.clientId = BuildConfig.SIMKL_CLIENT_ID
            }
        }
    }

    fun effectiveClientId(): String = _customClientId.value.ifBlank { BuildConfig.SIMKL_CLIENT_ID }

    suspend fun saveClientId(clientId: String) {
        dataStore.setCustomClientId(clientId)
    }
}
