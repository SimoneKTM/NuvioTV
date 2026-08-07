package com.nuvio.tv.ui.screens.settings

import android.util.Log
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
    private val onApproved: (String?) -> Unit
) {
    private val _state = MutableStateFlow(TrackerQrLoginState(isConfigured = api.isConfigured))
    val state: StateFlow<TrackerQrLoginState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        if (_state.value.isStarting || _state.value.isPolling) return
        if (!api.isConfigured) {
            _state.update {
                it.copy(errorMessage = "QR login relay is not configured on this build")
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
                            it.copy(
                                isStarting = false,
                                session = session,
                                statusMessage = "Scan the QR code or open the URL on your phone"
                            )
                        }
                        startPolling(session)
                    },
                    onFailure = { error ->
                        Log.w(TAG, "QR login start failed provider=$providerId error=${error.message}")
                        _state.update {
                            it.copy(
                                isStarting = false,
                                errorMessage = error.message ?: "Failed to start QR login"
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
                        errorMessage = error.message ?: "Failed to start QR login"
                    )
                }
            }
        }
    }

    fun retryPolling() {
        val session = _state.value.session ?: return
        if (_state.value.isPolling) return
        startPolling(session)
    }

    fun cancel() {
        cancelPolling()
        _state.update {
            it.copy(
                session = null,
                isStarting = false,
                isPolling = false,
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
                        _state.update { it.copy(statusMessage = "Waiting for approval on your phone") }
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
                                errorMessage = "QR login session expired. Try again."
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
