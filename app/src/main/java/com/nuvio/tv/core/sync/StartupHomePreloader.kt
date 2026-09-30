package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.TraktTop10Repository
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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
 * Readiness is dynamic: phases are re-attempted until every one of them has
 * actually satisfied (no timeout), so the post-profile loading screen holds
 * for exactly as long as the data needs. A hard [TOTAL_DEADLINE_MS] cap
 * force-releases the gate so a slow or unreachable source can never block
 * Home for more than a minute.
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
    private val traktTop10Repository: TraktTop10Repository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val experienceModeDataStore: ExperienceModeDataStore,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val metadataTextTranslator: MetadataTextTranslator,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore
) {
    companion object {
        private const val TAG = "StartupHomePreloader"
        private const val PHASE_TIMEOUT_MS = 20_000L
        private const val TOTAL_DEADLINE_MS = 60_000L
        private const val RETRY_DELAY_MS = 2_000L
    }

    // Warm-ups run on a background-priority thread: on low-end TV sticks the
    // preload must yield CPU to the UI (it still runs at full speed whenever
    // the UI is idle) instead of competing with the first Home frames.
    private val preloadDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "startup-home-preload").apply {
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + preloadDispatcher)
    private var preloadJob: Job? = null
    private var profileWatchJob: Job? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _started = MutableStateFlow(false)

    fun ensureStarted() {
        if (!_started.compareAndSet(expect = false, update = true)) return

        // Fire-and-forget: pre-download the translation model once so the first
        // detail screen doesn't stall waiting for a multi-MB ML Kit download.
        scope.launch { warmUpTranslationModel() }

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

    private suspend fun warmUpTranslationModel() {
        runCatching {
            val language = tmdbSettingsDataStore.settings.first().language
            metadataTextTranslator.warmUp(language)
        }.onFailure { e ->
            Log.w(TAG, "Translation model warm-up failed: ${e.message}")
        }
    }

    private fun startPreload() {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            _ready.value = false
            val startedAt = android.os.SystemClock.elapsedRealtime()
            // Keep re-attempting until every phase has genuinely completed, so
            // the black loading screen waits exactly as long as the data needs;
            // the hard deadline below guarantees Home after one minute max.
            var complete = false
            while (!complete) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val remaining = TOTAL_DEADLINE_MS - elapsed
                if (remaining <= 0L) break
                complete = try {
                    withTimeoutOrNull(remaining) { runPreloadPhases() } == true
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Preload attempt failed: ${e.message}")
                    false
                }
                if (!complete) {
                    val left = TOTAL_DEADLINE_MS - (android.os.SystemClock.elapsedRealtime() - startedAt)
                    if (left <= RETRY_DELAY_MS) break
                    delay(RETRY_DELAY_MS)
                }
            }
            _ready.value = true
            Log.d(
                TAG,
                "Home preload ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(complete=$complete) profile=${profileManager.activeProfileId.value}"
            )
        }
    }

    /** Runs every warm-up phase once; returns true only when all of them satisfied. */
    private suspend fun runPreloadPhases(): Boolean {
        var complete = true

        // Kick warm-ups early (no-ops if already running).
        catalogRepository.warmUp()
        calendarRepository.warmUp()
        traktTop10Repository.warmUp()

        suspend fun phase(block: suspend () -> Unit) {
            if (withTimeoutOrNull(PHASE_TIMEOUT_MS) { block() } == null) complete = false
        }

        phase { profileManager.activeProfileReady.first { it } }
        phase { addonRepository.getInstalledAddons().first() }
        phase { layoutPreferenceDataStore.hasChosenLayout.first() }
        phase { experienceModeDataStore.mode.first() }
        phase { layoutPreferenceDataStore.homeCatalogOrderKeys.first() }
        phase { catalogRepository.warmComplete.first { it } }
        // Touch CW disk cache so first Home render hits warm FS state.
        phase {
            cwEnrichmentCache.getInProgressSnapshot()
            cwEnrichmentCache.getNextUpSnapshot()
        }

        return complete
    }
}
