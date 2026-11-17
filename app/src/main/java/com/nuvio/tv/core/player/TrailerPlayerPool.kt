package com.nuvio.tv.core.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Application-scoped singleton that holds a single ExoPlayer instance dedicated to
 * trailer/preview playback on the home screen.
 *
 * Creating and tearing down ExoPlayer for every poster focus is extremely expensive
 * (codec init, hardware decoder allocation). This pool keeps one instance alive and
 * reuses it across focus changes. The player is stopped and cleared between uses but
 * never released until the process dies or [release] is explicitly called.
 *
 * When the full-screen player needs hardware decoders, call [yield] to free
 * codec resources without destroying the instance. Call [reclaim] when returning to
 * the home screen to lazily rebuild if needed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Singleton
class TrailerPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore
) {
    companion object {
        private const val TAG = "TrailerPlayerPool"

        /** Hard ceiling for the shared preview buffer, independent of bitrate. */
        private const val TRAILER_TARGET_BUFFER_BYTES_720P = 8 * 1024 * 1024

        /** The 1080p opt-in keeps a slightly larger buffer (still tiny vs the old 120s cap). */
        private const val TRAILER_TARGET_BUFFER_BYTES_1080P = 12 * 1024 * 1024

        /**
         * Memory gate for the 1080p opt-in: below this much total RAM the
         * preview stays at 720p no matter what the setting says — a 1080p
         * decoder plus bigger buffers is exactly what got lmkd to kill the
         * process on 1-2 GB sticks.
         */
        private const val LOW_RAM_THRESHOLD_BYTES = 3L * 1024 * 1024 * 1024

        /**
         * Dopo un [trim] dovuto a pressione di memoria non ricreare subito il decoder:
         * sotto lmkd un nuovo codec appena rilasciato risomma il processo. La UI
         * mostra lo sfondo statico finché il cooldown non scade.
         */
        private const val TRAILER_TRIM_COOLDOWN_MS = 60_000L
    }

    private var _player: ExoPlayer? = null
    private val yielded = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile
    private var trimCooldownUntilMs = 0L

    /** Quality the current instance was built with; -1 until first build. */
    @Volatile
    private var appliedQualityHeight: Int = -1

    /**
     * Returns the shared trailer ExoPlayer, creating it lazily if needed.
     * Returns null only if [release] was called (process shutdown) or while a
     * post-[trim] cooldown is active (system under memory pressure).
     */
    fun acquire(): ExoPlayer? {
        if (released.get()) return null
        if (android.os.SystemClock.elapsedRealtime() < trimCooldownUntilMs) return null
        // Sistema in zona di kill: niente nuovo decoder. La home mostra lo sfondo
        // statico (già gestito da acquire() == null) finché non si respira.
        if (com.nuvio.tv.core.diagnostics.MemoryWatch.underPressure) return null
        if (yielded.get()) {
            // Reclaim was not called yet but someone wants the player — rebuild.
            reclaim()
        }
        // The quality setting lives in Impostazioni: honour a change without
        // restarting the app by rebuilding the track selector on next use.
        val requested = requestedQualityHeight()
        val existing = _player
        if (existing != null && appliedQualityHeight != requested) {
            Log.i(TAG, "Trailer quality changed (${appliedQualityHeight}p -> ${requested}p): rebuilding player")
            runCatching { existing.stop() }
            runCatching { existing.clearMediaItems() }
            runCatching { existing.release() }
            _player = null
        }
        return _player ?: createPlayer(requested).also { _player = it }
    }

    /** Current ceiling from Impostazioni (720 default, 1080 opt-in). */
    private fun requestedQualityHeight(): Int = runBlocking {
        runCatching { trailerSettingsDataStore.settings.first().maxVideoHeight }
            .getOrDefault(720)
    }

    /**
     * Stops playback and clears media but keeps the instance alive for reuse.
     * Call this when the trailer is no longer visible (poster lost focus, screen change).
     */
    fun stop() {
        _player?.let { player ->
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
            }
        }
    }

    /**
     * Releases codec resources so the detail-screen player can claim hardware decoders.
     * The ExoPlayer instance is released here; [reclaim] will create a fresh one.
     */
    fun yield() {
        if (yielded.compareAndSet(false, true)) {
            Log.d(TAG, "Yielding trailer player for detail playback")
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
        }
    }

    /**
     * Re-creates the player after a [yield]. Safe to call multiple times.
     */
    fun reclaim() {
        if (released.get()) return
        if (yielded.compareAndSet(true, false)) {
            Log.d(TAG, "Reclaiming trailer player")
            // Player will be lazily created on next acquire()
        }
    }

    /**
     * Frees the trailer player (codec + preview buffer) when the system reports
     * memory pressure. Unlike [release] this keeps the pool usable: the next
     * [acquire] rebuilds a fresh instance.
     */
    fun trim() {
        if (released.get() || _player == null) return
        Log.i(TAG, "Trimming trailer player after memory pressure")
        _player?.let { player ->
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            runCatching { player.release() }
        }
        _player = null
        yielded.set(true)
        trimCooldownUntilMs = android.os.SystemClock.elapsedRealtime() + TRAILER_TRIM_COOLDOWN_MS
    }

    /**
     * Permanently releases the player. Called on process death / Application.onTerminate.
     */
    fun release() {
        if (released.compareAndSet(false, true)) {
            _player?.let { player ->
                runCatching { player.stop() }
                runCatching { player.clearMediaItems() }
                runCatching { player.release() }
            }
            _player = null
        }
    }

    private fun createPlayer(requestedHeight: Int): ExoPlayer {
        val maxHeight = qualityCeiling(requestedHeight)
        appliedQualityHeight = requestedHeight
        val forceNative = runBlocking {
            playerSettingsDataStore.nuvioPerformanceModeEnabled.first()
        }
        Log.i(
            TAG,
            "Creating shared trailer ExoPlayer: quality=${maxHeight}p " +
                "(requested=${requestedHeight}p) forceNativeAllocation=$forceNative"
        )
        val targetBufferBytes =
            if (maxHeight >= 1080) TRAILER_TARGET_BUFFER_BYTES_1080P
            else TRAILER_TARGET_BUFFER_BYTES_720P
        val loadControlBuilder = DefaultLoadControl.Builder()
            // A trailer preview is a few seconds long and runs behind the home screen, so it
            // gets a byte ceiling as well as a short time window: without targetBufferBytes
            // the only limit is 120s of video, which at forced-highest bitrate reaches
            // 30-150MB on a 1GB device and gets the process killed by lmkd.
            .setTargetBufferBytes(targetBufferBytes)
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 20_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
        if (forceNative) {
            val allocator = DefaultAllocator(
                /* trimOnReset = */ true,
                /* individualAllocationSize = */ 65536,
                /* initialAllocationCount = */ 0,
                /* forceNativeAllocation = */ true
            )
            loadControlBuilder.setAllocator(allocator)
        }
        val loadControl = loadControlBuilder.build()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Ceiling per la qualità scelta in Impostazioni (720p di
                    // default, 1080p opt-in con gate memoria in qualityCeiling).
                    // La vecchia configurazione annullava ogni vincolo
                    // (.clearVideoSizeConstraints + setMaxVideoSize(MAX,MAX)) e
                    // forzava la variante più alta disponibile su YouTube, fino a 4K:
                    // i buffer del decoder da ~130 MB di ION più la memoria GPU
                    // facevano esplodere il processo a ~700 MB e lmkd lo uccideva
                    // su queste Stick da 1 GB. A 720p l'anteprima occupa ~20 MB.
                    .setMaxVideoSize(if (maxHeight >= 1080) 1920 else 1280, maxHeight)
                    .setForceHighestSupportedBitrate(true)
            )
        }
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(
                DefaultBandwidthMeter.Builder(context)
                    // 8 Mbps per 720p, 16 per 1080p: sufficienti per la variante
                    // più alta del tetto scelto, e impediscono che la stima iniziale
                    // spinga subito la selezione verso risoluzioni più alte.
                    .setInitialBitrateEstimate(if (maxHeight >= 1080) 16_000_000L else 8_000_000L)
                    .build()
            )
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    /**
     * 1080p è opt-in ma resta subordinato alla memoria disponibile: sotto la
     * soglia [LOW_RAM_THRESHOLD_BYTES] o con il sistema in pressione
     * l'anteprima scende a 720p anche se l'utente ha scelto 1080p.
     */
    private fun qualityCeiling(requestedHeight: Int): Int {
        if (requestedHeight <= 720) return 720
        if (com.nuvio.tv.core.diagnostics.MemoryWatch.underPressure) return 720
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        activityManager?.getMemoryInfo(memoryInfo)
        val totalBytes = memoryInfo.totalMem
        return if (totalBytes in 1L until LOW_RAM_THRESHOLD_BYTES) {
            Log.i(TAG, "1080p gated: device has ${totalBytes / (1024 * 1024 * 1024)}GB RAM < 3GB")
            720
        } else {
            requestedHeight
        }
    }
}
