package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.kitsu.KitsuAuthError
import com.nuvio.tv.data.kitsu.KitsuAuthRepository
import com.nuvio.tv.data.kitsu.KitsuConnectResult
import com.nuvio.tv.data.kitsu.KitsuConnectionMode
import com.nuvio.tv.data.kitsu.KitsuSyncRepository
import com.nuvio.tv.data.trackerqr.TrackerQrApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KitsuSettingsUiState(
    val mode: KitsuConnectionMode = KitsuConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val authorizeUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val qrLogin: TrackerQrLoginState = TrackerQrLoginState()
)

@HiltViewModel
class KitsuSettingsViewModel @Inject constructor(
    private val authRepository: KitsuAuthRepository,
    private val syncRepository: KitsuSyncRepository,
    trackerQrApi: TrackerQrApi,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        KitsuSettingsUiState(
            credentialsConfigured = authRepository.hasRequiredCredentials(),
            authorizeUrl = authRepository.authorizeUrl(),
            qrLogin = TrackerQrLoginState(isConfigured = trackerQrApi.isConfigured)
        )
    )
    val uiState: StateFlow<KitsuSettingsUiState> = _uiState.asStateFlow()
    private var connectJob: Job? = null

    private val qrLoginController = TrackerQrLoginController(
        api = trackerQrApi,
        scope = viewModelScope,
        providerId = PROVIDER_ID,
        onApproved = { payload -> connect(payload.orEmpty()) }
    )

    init {
        viewModelScope.launch {
            authRepository.state.collectLatest { state ->
                _uiState.update { current ->
                    current.copy(
                        mode = state.mode,
                        credentialsConfigured = authRepository.hasRequiredCredentials(),
                        username = state.username,
                        errorMessage = state.error?.message()
                    )
                }
            }
        }
        viewModelScope.launch {
            qrLoginController.state.collectLatest { qrState ->
                _uiState.update { it.copy(qrLogin = qrState) }
            }
        }
    }

    fun startQrLogin() {
        if (_uiState.value.isLoading) return
        qrLoginController.start()
    }

    fun retryQrLogin() {
        qrLoginController.retryPolling()
    }

    fun cancelQrLogin() {
        qrLoginController.cancel()
    }

    fun connect(token: String) {
        if (connectJob?.isActive == true || _uiState.value.isLoading) return
        val rawToken = token.trim()
        if (!authRepository.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    credentialsConfigured = false,
                    errorMessage = context.getString(R.string.kitsu_missing_credentials)
                )
            }
            return
        }
        if (rawToken.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = context.getString(R.string.kitsu_error_invalid_token))
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
        connectJob = viewModelScope.launch {
            try {
                when (val result = authRepository.connectToken(rawToken)) {
                    KitsuConnectResult.Connected -> {
                        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
                        _uiState.update {
                            it.copy(statusMessage = context.getString(R.string.kitsu_status_connected))
                        }
                    }
                    is KitsuConnectResult.Failed -> {
                        _uiState.update { it.copy(errorMessage = result.error.message()) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(errorMessage = error.toUserMessage()) }
            } finally {
                connectJob = null
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    statusMessage = context.getString(R.string.kitsu_status_syncing)
                )
            }
            syncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            val error = syncRepository.state.value.errorMessage
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = if (error == null) {
                        context.getString(R.string.kitsu_status_synced)
                    } else {
                        null
                    },
                    errorMessage = error
                )
            }
        }
    }

    fun onDisconnect() {
        viewModelScope.launch {
            authRepository.disconnect()
            syncRepository.clearCurrentProfile()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = context.getString(R.string.kitsu_status_disconnected),
                    errorMessage = null
                )
            }
        }
    }

    private fun KitsuAuthError.message(): String = when (this) {
        KitsuAuthError.MISSING_CLIENT_ID -> context.getString(R.string.kitsu_missing_credentials)
        KitsuAuthError.INVALID_TOKEN -> context.getString(R.string.kitsu_error_invalid_token)
        KitsuAuthError.AUTHORIZATION_REVOKED -> context.getString(R.string.kitsu_error_authorization_revoked)
        KitsuAuthError.NETWORK -> context.getString(R.string.kitsu_error_network)
    }

    private fun Throwable.toUserMessage(): String =
        (this as? com.nuvio.tv.data.kitsu.KitsuAuthException)?.message
            ?: message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.kitsu_error_network)

    private companion object {
        const val PROVIDER_ID = "kitsu"
    }
}