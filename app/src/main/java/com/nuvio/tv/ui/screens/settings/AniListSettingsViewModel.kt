package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingSourceController
import com.nuvio.tv.data.anilist.AniListAuthError
import com.nuvio.tv.data.anilist.AniListAuthRepository
import com.nuvio.tv.data.anilist.AniListConnectResult
import com.nuvio.tv.data.anilist.AniListConnectionMode
import com.nuvio.tv.data.anilist.AniListSyncRepository
import com.nuvio.tv.data.trackerqr.TrackerQrApi
import com.nuvio.tv.domain.model.LibrarySourceMode
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

data class AniListSettingsUiState(
    val mode: AniListConnectionMode = AniListConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val authorizeUrl: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val qrLogin: TrackerQrLoginState = TrackerQrLoginState()
)

@HiltViewModel
class AniListSettingsViewModel @Inject constructor(
    private val authRepository: AniListAuthRepository,
    private val syncRepository: AniListSyncRepository,
    private val sourceController: TrackingSourceController,
    trackerQrApi: TrackerQrApi,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AniListSettingsUiState(
            credentialsConfigured = authRepository.hasRequiredCredentials(),
            authorizeUrl = authRepository.authorizeUrl(),
            qrLogin = TrackerQrLoginState(isConfigured = trackerQrApi.isConfigured)
        )
    )
    val uiState: StateFlow<AniListSettingsUiState> = _uiState.asStateFlow()
    private var connectJob: Job? = null

    private val qrLoginController = TrackerQrLoginController(
        api = trackerQrApi,
        scope = viewModelScope,
        providerId = PROVIDER_ID,
        getString = context::getString,
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
                    errorMessage = context.getString(R.string.anilist_missing_credentials)
                )
            }
            return
        }
        if (rawToken.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = context.getString(R.string.anilist_error_invalid_token))
            }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
        connectJob = viewModelScope.launch {
            try {
                when (val result = authRepository.connectToken(rawToken)) {
                    AniListConnectResult.Connected -> {
                        syncRepository.refresh(TrackingRefreshIntent.INVALIDATED)
                        sourceController.autoSelectLibrarySource(LibrarySourceMode.ANILIST)
                        _uiState.update {
                            it.copy(statusMessage = context.getString(R.string.anilist_status_connected))
                        }
                    }
                    is AniListConnectResult.Failed -> {
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
                    statusMessage = context.getString(R.string.anilist_status_syncing)
                )
            }
            syncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            val error = syncRepository.state.value.errorMessage
            _uiState.update {
                it.copy(
                    isLoading = false,
                    statusMessage = if (error == null) {
                        context.getString(R.string.anilist_status_synced)
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
                    statusMessage = context.getString(R.string.anilist_status_disconnected),
                    errorMessage = null
                )
            }
        }
    }

    private fun AniListAuthError.message(): String = when (this) {
        AniListAuthError.MISSING_CLIENT_ID -> context.getString(R.string.anilist_missing_credentials)
        AniListAuthError.INVALID_TOKEN -> context.getString(R.string.anilist_error_invalid_token)
        AniListAuthError.AUTHORIZATION_REVOKED -> context.getString(R.string.anilist_error_authorization_revoked)
        AniListAuthError.NETWORK -> context.getString(R.string.anilist_error_network)
    }

    private fun Throwable.toUserMessage(): String =
        (this as? com.nuvio.tv.data.anilist.AniListAuthException)?.message
            ?: message?.takeIf(String::isNotBlank)
            ?: context.getString(R.string.anilist_error_network)

    private companion object {
        const val PROVIDER_ID = "anilist"
    }
}