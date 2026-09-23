package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preloads Home-critical data during the startup splash and profile selection
 * so the first Home frame is already warm.
 *
 * Waits for: active profile, installed addons, layout/experience prefs,
 * catalog first-page warm-up, calendar warm-up, and a CW disk-cache touch.
 */
@Singleton
class StartupHomePreloader @Inject constructor(
    private val profileManager: com.nuvio.tv.core.profile.ProfileManager,
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val calendarRepository: CalendarRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache
) {
    companion object {
        private const val TAG = "StartupHomePreloader"
        private const val PHASE_TIMEOUT_MS = 12_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null
    private var profileWatchJob: Job? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _started = MutableStateFlow(false)

    fun ensureStarted() {
        if (!_started.compareAndSet(expect = false, update = true)) return

        profileWatchJob = scope.launch {
            var lastProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { profileId ->
                if (profileId != lastProfileId) {
                    lastProfileId = profileId
                    _ready.value = false
                    startPreload()
                }
            }
        }
        startPreload()
    }

    private fun startPreload() {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            _ready.value = false
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                // Kick warm-ups early (no-ops if already running).
                catalogRepository.warmUp()
                calendarRepository.warmUp()

                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    profileManager.activeProfileReady.first { it }
                }
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    addonRepository.getInstalledAddons().first()
                }
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    layoutPreferenceDataStore.hasChosenLayout.first()
                }
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    experienceModeDataStore.mode.first()
                }
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    layoutPreferenceDataStore.homeCatalogOrderKeys.first()
                }
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    catalogRepository.warmComplete.first { it }
                }
                // Touch CW disk cache so first Home render hits warm FS state.
                withTimeoutOrNull(PHASE_TIMEOUT_MS) {
                    runCatching {
                        cwEnrichmentCache.getInProgressSnapshot()
                        cwEnrichmentCache.getNextUpSnapshot()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preload interrupted: ${e.message}")
            }
            _ready.value = true
            Log.d(
                TAG,
                "Home preload ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms profile=${profileManager.activeProfileId.value}"
            )
        }
    }
}
