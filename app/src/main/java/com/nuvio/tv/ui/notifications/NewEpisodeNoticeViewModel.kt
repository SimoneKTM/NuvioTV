package com.nuvio.tv.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.NewEpisodeNoticeService
import com.nuvio.tv.domain.model.NewEpisodeNotice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewEpisodeNoticeUiState(
    val isChecking: Boolean = false,
    val notices: List<NewEpisodeNotice> = emptyList(),
    val showBanner: Boolean = false
)

@HiltViewModel
class NewEpisodeNoticeViewModel @Inject constructor(
    private val service: NewEpisodeNoticeService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewEpisodeNoticeUiState())
    val uiState: StateFlow<NewEpisodeNoticeUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null
    private var lastCheckAtMs = 0L

    /**
     * Called every time the app comes to the foreground. Re-shows the notices
     * already found and refreshes them when the last check is stale.
     */
    fun onAppOpen() {
        val cached = _uiState.value.notices
        if (cached.isNotEmpty()) {
            _uiState.update { it.copy(showBanner = true) }
        }
        if (checkJob?.isActive == true) return
        val lastCheck = lastCheckAtMs
        if (lastCheck > 0 && System.currentTimeMillis() - lastCheck < RECHECK_MIN_INTERVAL_MS) return

        checkJob = viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true) }
            val result = runCatching { service.computeNotices() }
            lastCheckAtMs = System.currentTimeMillis()
            result
                .onSuccess { notices ->
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            notices = notices,
                            showBanner = notices.isNotEmpty()
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isChecking = false) }
                }
        }
    }

    fun dismiss() {
        _uiState.update { it.copy(showBanner = false) }
    }

    private companion object {
        const val RECHECK_MIN_INTERVAL_MS = 60_000L
    }
}
