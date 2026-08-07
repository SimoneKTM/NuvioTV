package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.mal.MalAuthError
import com.nuvio.tv.data.mal.MalAuthRepository
import com.nuvio.tv.data.mal.MalConnectResult
import com.nuvio.tv.data.mal.MalConnectionMode
import com.nuvio.tv.data.mal.MalSyncRepository
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

data class MalSettingsUiState(
    val mode: MalConnectionMode = MalConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val authorizeUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class MalSettingsViewModel @Inject constructor(
    private val authRepository: MalAuthRepository,
    private val syncRepository: MalSyncRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        MalSettingsUiState(
            credentialsConfigured = authRepository.hasRequiredCredentials(),
            authorizeUrl = authRepository.buildAuthorizeUrl()
        )
    )
    val uiState: StateFlow<MalSettingsUiState> = _uiState.asStateFlow()
    private var connectJob: Job? = null

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
    }

    fun connect(token: String) {
        if (connectJob?.isActive == true || _uiState.value.isLoading) return
        val rawToken = token.trim()
        if (!authRepository.hasRequiredCredentials()) {
            _uiState.update {
                it.copy(
                    credentialsConfigured = false,
                    errorMessage = context.getString(R.string.mal_missing_credentials)
                )
            }
            return
        }
        if (rawToken.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = context.getString(R.string.mal_error_invalid_token))
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
        connectJob = viewModelScope.launch {
            try {
                when (val result = authRepository.connectToken(rawToken)) {
                    MalConnectResult.Connected -> {
                        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
                        _uiState.update {
                            it.copy(statusMessage = context.getString(R.string.mal_status_connected))
                        }
                    }
                    is MalConnectResult.Failed -> {
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
                    statusMessage = context.getString(R.string.mal_status_syncing)
                )
            }
            syncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            val error = syncRepository.state.value.errorMessage
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = if (error == null) {
                        context.getString(R.string.mal_status_synced)
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
                    statusMessage = context.getString(R.string.mal_status_disconnected),
                    errorMessage = null
                )
            }
        }
    }

    private fun MalAuthError.message(): String = when (this) {
        MalAuthError.MISSING_CLIENT_ID -> context.getString(R.string.mal_missing_credentials)
        MalAuthError.INVALID_TOKEN -> context.getString(R.string.mal_error_invalid_token)
        MalAuthError.AUTHORIZATION_REVOKED -> context.getString(R.string.mal_error_authorization_revoked)
        MalAuthError.NETWORK -> context.getString(R.string.mal_error_network)
    }

    private fun Throwable.toUserMessage(): String =
        (this as? com.nuvio.tv.data.mal.MalAuthException)?.message
            ?: message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.mal_error_network)
}