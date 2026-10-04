package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.perf.FluidModeState
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExperienceModeDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StartupWarmupPreferences
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.repository.TraktTop10Repository
import com.nuvio.tv.data.translation.MetadataTextTranslator
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.extractYear
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
 * catalog first-page warm-up, the on-disk image warm-up for every Home card,
 * calendar warm-up, and a CW disk-cache touch.
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
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val fluidModeState: FluidModeState,
    private val homeImagePreloader: HomeImagePreloader,
    private val tmdbService: TmdbService,
    private val trailerService: TrailerService,
    private val startupWarmupPreferences: StartupWarmupPreferences
) {
    companion object {
        private const val TAG = "StartupHomePreloader"

        // Fluid Mode: longer phases plus a retry loop with a hard deadline, so the
        // loading screen stays up until the catalogs are actually warm.
        private const val PHASE_TIMEOUT_MS = 40_000L
        private const val TOTAL_DEADLINE_MS = 120_000L
        private const val RETRY_DELAY_MS = 2_000L

        // Classic (Fluid Mode off): the single pass of the build before the speed-ups.
        private const val CLASSIC_PHASE_TIMEOUT_MS = 20_000L

        // Trailer warm-up: bounded so a slow extraction never holds the gate.
        private const val TRAILER_WARM_BUDGET_MS = 30_000L
        private const val TRAILER_WARM_COUNT = 6
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

    // The ML Kit warm-up is mostly network (model download) and used to share the
    // background-priority thread above, so it queued behind every preload phase and
    // routinely finished after the first Home row had already asked for a
    // translation — leaving that row to wait on the download itself.
    private val warmUpDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "translation-warm-up").apply {
            priority = Thread.NORM_PRIORITY
        }
    }.asCoroutineDispatcher()
    private val warmUpScope = CoroutineScope(SupervisorJob() + warmUpDispatcher)
    private var preloadJob: Job? = null
    private var profileWatchJob: Job? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _started = MutableStateFlow(false)

    fun ensureStarted() {
        if (!_started.compareAndSet(expect = false, update = true)) return

        // Fire-and-forget: pre-download the translation model once so the first
        // detail screen doesn't stall waiting for a multi-MB ML Kit download.
        warmUpScope.launch { warmUpTranslationModel() }

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
            val fluidPerformance = fluidModeState.isEnabled()
            var complete = false
            if (fluidPerformance) {
                // Keep re-attempting until every phase has genuinely completed, so
                // the black loading screen waits exactly as long as the data needs;
                // the hard deadline guarantees Home after two minutes max.
                while (!complete) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    val remaining = TOTAL_DEADLINE_MS - elapsed
                    if (remaining <= 0L) break
                    complete = try {
                        withTimeoutOrNull(remaining) { runPreloadPhases(PHASE_TIMEOUT_MS) } == true
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
            } else {
                // Classic: a single pass with the classic per-phase timeout, then
                // Home either way — the structure of the build before the speed-ups.
                complete = try {
                    runPreloadPhases(CLASSIC_PHASE_TIMEOUT_MS)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Preload interrupted: ${e.message}")
                    false
                }
            }
            _ready.value = true
            // Solo se tutto è andato davvero a buon fine: il prossimo avvio è caldo.
            if (complete) {
                runCatching { startupWarmupPreferences.markFirstHomeReady() }
            }
            Log.d(
                TAG,
                "Home preload ready in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                    "(fluid=$fluidPerformance complete=$complete) profile=${profileManager.activeProfileId.value}"
            )
        }
    }

    /** Runs every warm-up phase once; returns true only when all of them satisfied. */
    private suspend fun runPreloadPhases(phaseTimeoutMs: Long): Boolean {
        var complete = true

        // Kick warm-ups early (no-ops if already running).
        catalogRepository.warmUp()
        calendarRepository.warmUp()
        traktTop10Repository.warmUp()

        suspend fun phase(block: suspend () -> Unit) {
            if (withTimeoutOrNull(phaseTimeoutMs) { block() } == null) complete = false
        }

        phase { profileManager.activeProfileReady.first { it } }
        phase { addonRepository.getInstalledAddons().first() }
        phase { layoutPreferenceDataStore.hasChosenLayout.first() }
        phase { experienceModeDataStore.mode.first() }
        phase { layoutPreferenceDataStore.homeCatalogOrderKeys.first() }
        // Held on purpose: Home's own row loaders are heavy on a stick, so the
        // loading screen stays up until every catalog answered its first page
        // (still capped by the phase timeout and the total deadline force-release).
        phase { catalogRepository.warmComplete.first { it } }
        // Immagini: scarica su disco poster/sfondi/loghi delle prime pagine, così
        // Home parte già pronta e lo scroll non scarica più nulla.
        phase { homeImagePreloader.warmImages() }
        // Trailer: best effort con budget proprio (non blocca la partenza).
        phase { withTimeoutOrNull(TRAILER_WARM_BUDGET_MS) { warmTrailers() } }
        // Touch CW disk cache so first Home render hits warm FS state.
        phase {
            cwEnrichmentCache.getInProgressSnapshot()
            cwEnrichmentCache.getNextUpSnapshot()
        }

        return complete
    }

    /** Risolve gli URL dei trailer dei primi titoli per farli partire subito in Home. */
    private suspend fun warmTrailers() {
        if (!AppFeaturePolicy.inAppTrailerPlaybackEnabled) return
        val items = catalogRepository.firstPageRows()
            .flatMap { it.items }
            .distinctBy { it.id }
            .take(TRAILER_WARM_COUNT)
        if (items.isEmpty()) return
        items.forEach { item ->
            val tmdbId = runCatching { tmdbService.ensureTmdbId(item.id, item.apiType) }.getOrNull()
            runCatching {
                trailerService.getTrailerPlaybackSource(
                    title = item.name,
                    year = extractYear(item.releaseInfo),
                    tmdbId = tmdbId,
                    type = item.apiType
                )
            }
        }
        Log.d(TAG, "Warmed ${items.size} trailer lookups")
    }
}
