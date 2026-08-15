package com.nuvio.tv.ui.screens.settings

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.trackerqr.TrackerQrApi
import com.nuvio.tv.data.trackerqr.TrackerQrPollResult
import com.nuvio.tv.data.trackerqr.TrackerQrSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TrackerQrLoginState(
    val isConfigured: Boolean = false,
    val session: TrackerQrSession? = null,
    val isStarting: Boolean = false,
    val isPolling: Boolean = false,
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

/**
 * Shared QR relay sign-in coordinator for the AniList/Kitsu/MAL account dialogs.
 *
 * Flow:
 *  1. [start] asks the relay for a fresh session (user code + authorization URL).
 *  2. The dialog renders a QR of the URL plus the code (like Trakt/Simkl device auth).
 *  3. [polling] loop watches the relay until the phone completes the sign-in on the
 *     relay-hosted page, then hands the approved payload to [onApproved].
 */
class TrackerQrLoginController(
    private val api: TrackerQrApi,
    private val scope: CoroutineScope,
    private val providerId: String,
    private val getString: (Int) -> String,
    private val onApproved: (String?) -> Unit
) {
    private val _state = MutableStateFlow(TrackerQrLoginState(isConfigured = api.isConfigured))
    val state: StateFlow<TrackerQrLoginState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var pendingCredentials: Pair<String, String>? = null

    fun start() {
        if (_state.value.isStarting || _state.value.isPolling) return
        if (!api.isConfigured) {
            _state.update {
                it.copy(errorMessage = getString(R.string.qr_login_relay_not_configured))
            }
            return
        }
        cancelPolling()
        _state.update {
            it.copy(
                isStarting = true,
                session = null,
                statusMessage = null,
                errorMessage = null
            )
        }
        scope.launch {
            try {
                val result = api.startSession(providerId)
                result.fold(
                    onSuccess = { session ->
                        Log.d(TAG, "QR login session started provider=$providerId code=${session.userCode}")
                        _state.update {
                            it.copy(isStarting = false, session = session)
                        }
                        startPolling(session)
                        pendingCredentials?.let { (username, password) ->
                            pendingCredentials = null
                            submitCredentials(session.userCode, username, password)
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "QR login start failed provider=$providerId error=${error.message}")
                        _state.update {
                            it.copy(
                                isStarting = false,
                                errorMessage = error.message ?: getString(R.string.qr_login_start_failed)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "QR login start threw provider=$providerId error=${error.message}")
                _state.update {
                    it.copy(
                        isStarting = false,
                        errorMessage = error.message ?: getString(R.string.qr_login_start_failed)
                    )
                }
            }
        }
    }

    /**
     * Submits Kitsu username/password directly to the relay's login page.
     * A session is started first if one is not already active, then the relay
     * exchanges the credentials for tokens and marks the session approved.
     */
    fun submitKitsuCredentials(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.update {
                it.copy(errorMessage = getString(R.string.qr_login_enter_credentials))
            }
            return
        }
        val session = _state.value.session
        if (session == null) {
            pendingCredentials = username to password
            start()
            return
        }
        if (_state.value.isSubmitting) return
        submitCredentials(session.userCode, username, password)
    }

    private fun submitCredentials(userCode: String, username: String, password: String) {
        val session = _state.value.session
        _state.update {
            it.copy(
                isSubmitting = true,
                statusMessage = null,
                errorMessage = null
            )
        }
        scope.launch {
            val result = runCatching { api.submitKitsuCredentials(userCode, username, password) }
                .getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = {
                    Log.d(TAG, "Kitsu credentials accepted, waiting for approval")
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            statusMessage = getString(R.string.qr_login_signing_in)
                        )
                    }
                    if (session != null && !_state.value.isPolling) {
                        cancelPolling()
                        startPolling(session)
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "Kitsu credentials rejected error=${error.message}")
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            errorMessage = error.message ?: getString(R.string.qr_login_failed_retry)
                        )
                    }
                }
            )
        }
    }

    fun retryPolling() {
        val session = _state.value.session ?: return
        if (_state.value.isPolling) return
        startPolling(session)
    }

    fun cancel() {
        pendingCredentials = null
        cancelPolling()
        _state.update {
            it.copy(
                session = null,
                isStarting = false,
                isPolling = false,
                isSubmitting = false,
                statusMessage = null,
                errorMessage = null
            )
        }
    }

    private fun startPolling(session: TrackerQrSession) {
        cancelPolling()
        pollJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val interval = session.pollIntervalSeconds.coerceAtLeast(2)
                delay(interval * 1_000L)
                attempt += 1
                if (!isActive) break
                _state.update { it.copy(isPolling = true) }
                when (val result = api.pollSession(session.userCode, providerId)) {
                    TrackerQrPollResult.Pending -> {
                        Log.d(TAG, "QR login poll pending provider=$providerId attempt=$attempt")
                    }
                    is TrackerQrPollResult.Approved -> {
                        Log.d(TAG, "QR login approved provider=$providerId attempt=$attempt payload=${result.payload != null}")
                        cancelPolling()
                        onApproved(result.payload)
                    }
                    TrackerQrPollResult.Expired -> {
                        Log.w(TAG, "QR login expired provider=$providerId attempt=$attempt")
                        cancelPolling()
                        _state.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = getString(R.string.qr_login_expired)
                            )
                        }
                    }
                    is TrackerQrPollResult.Failed -> {
                        Log.w(TAG, "QR login poll failed provider=$providerId attempt=$attempt error=${result.message}")
                        cancelPolling()
                        _state.update {
                            it.copy(
                                isPolling = false,
                                errorMessage = result.message
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(isPolling = false) }
    }

    private companion object {
        const val TAG = "TrackerQrLoginController"
    }
}
