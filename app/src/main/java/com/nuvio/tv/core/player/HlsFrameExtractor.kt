package com.nuvio.tv.core.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts single video frames from adaptive streams (HLS/DASH) that
 * [android.media.MediaMetadataRetriever] cannot read.
 *
 * A dedicated, hidden [ExoPlayer] prepares the stream on a small off-screen
 * [ImageReader] surface, seeks to the requested position and hands back the
 * first rendered frame. The player stays prepared between calls so subsequent
 * frames of the same stream only cost a seek (no re-buffering).
 *
 * Player interaction always happens on the main thread; [getFrame] blocks the
 * calling (background) thread until a frame arrives or [REQUEST_TIMEOUT_MS]
 * expires.
 */
@UnstableApi
@Singleton
class HlsFrameExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SeekThumbs"
        private const val REQUEST_TIMEOUT_MS = 9_000L
        private const val SURFACE_WIDTH = 640
        private const val SURFACE_HEIGHT = 360
        private const val MAX_IMAGES = 3
        private const val FRAME_FALLBACK_DELAY_MS = 500L

        // Thumbnails need a single frame, not a smooth playback buffer: keep
        // the window tiny so a seek downloads one segment instead of ~10.
        private const val MIN_BUFFER_MS = 4_000
        private const val MAX_BUFFER_MS = 4_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000
        private const val TARGET_BUFFER_BYTES = 1_500_000
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val probedUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sniffedMime: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    private var player: ExoPlayer? = null
    private var playerKey: String? = null
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null

    private var pendingLatch: CountDownLatch? = null
    private var pendingResult: AtomicReference<Bitmap?>? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            // The image reader delivers the pixels; this is a safety net for
            // devices that queue the buffer slightly after the first-frame event.
            mainHandler.postDelayed({ deliverPendingFrame() }, FRAME_FALLBACK_DELAY_MS)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "hls frame player error: ${error.errorCodeName} ${error.message}")
            var cause: Throwable? = error.cause
            var depth = 0
            while (cause != null && depth < 4) {
                Log.w(TAG, "hls frame cause: ${cause.javaClass.simpleName} ${cause.message}")
                cause = cause.cause
                depth++
            }
            finish(null)
        }
    }

    /** Returns the frame closest to [positionMs] or null when extraction fails. */
    fun getFrame(
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        mimeType: String?
    ): Bitmap? {
        Log.d(TAG, "hls request pos=$positionMs mime=$mimeType headers=${headers.keys} url=${url.takeLast(90)}")
        // Skip the network sniff when the container is already clear from the
        // URL (extension or /playlist/ style path): saves a full round trip
        // before the first frame.
        if (sniffedMime[url] == null && mimeType == null &&
            PlayerMediaSourceFactory.inferMimeType(url, null) == null && probedUrls.add(url)
        ) {
            probe(url, headers)
        }
        val effectiveMimeType = mimeType
            ?: sniffedMime[url]?.takeIf { it.isNotBlank() }
            ?: PlayerMediaSourceFactory.inferMimeType(url, null)
        val result = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post { beginRequest(url, headers, positionMs, effectiveMimeType, latch, result) }

        val completed = try {
            latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            Log.w(TAG, "hls frame timeout pos=$positionMs")
            mainHandler.post { finish(null) }
        }
        return result.get()
    }

    /** Releases the hidden player and its surface. Safe to call repeatedly. */
    fun release() {        mainHandler.post {
            finish(null)
            releasePlayer()
            surface?.release()
            surface = null
            imageReader?.close()
            imageReader = null
        }
    }

    /** Diagnostic: reports what the stream URL actually returns. */
    private fun probe(url: String, headers: Map<String, String>) {
        runCatching {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) connection.setRequestProperty(name, value)
            }
            val code = connection.responseCode
            val type = connection.contentType
            val body = runCatching {
                connection.inputStream.bufferedReader().use { reader ->
                    CharArray(300).let { chars ->
                        val read = reader.read(chars)
                        if (read > 0) String(chars, 0, read) else ""
                    }
                }
            }.getOrDefault("")
            Log.d(TAG, "probe code=$code type=$type body=${body.replace('\n', ' ').replace('\r', ' ')}")
            sniffedMime[url] = when {
                body.contains("#EXTM3U") -> MimeTypes.APPLICATION_M3U8
                body.contains("<mpd") -> MimeTypes.APPLICATION_MPD
                else -> ""
            }
            connection.disconnect()
        }.onFailure { error ->
            Log.w(TAG, "probe failed: ${error.javaClass.simpleName} ${error.message}")
        }
    }

    private fun beginRequest(
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        mimeType: String?,
        latch: CountDownLatch,
        result: AtomicReference<Bitmap?>
    ) {
        // Abandon anything still in flight: only one frame at a time.
        finish(null)

        ensureSurface()
        ensurePlayer(url, headers, mimeType)
        val active = player
        if (active == null || surface == null) {
            result.set(null)
            latch.countDown()
            return
        }

        pendingLatch = latch
        pendingResult = result
        drainImages()

        active.playWhenReady = false
        if (active.playbackState == Player.STATE_IDLE) active.prepare()
        active.seekTo(positionMs)
        mainHandler.postDelayed({ deliverPendingFrame() }, FRAME_FALLBACK_DELAY_MS)
    }

    private fun ensureSurface() {
        if (imageReader != null) return
        val reader = ImageReader.newInstance(
            SURFACE_WIDTH,
            SURFACE_HEIGHT,
            // The video decoder outputs YUV; asking for RGBA makes BufferQueue
            // reject the frame with a producer/consumer format mismatch.
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        )
        reader.setOnImageAvailableListener({ available ->
            mainHandler.post { onImageAvailable(available) }
        }, mainHandler)
        imageReader = reader
        surface = reader.surface
    }

    private fun ensurePlayer(url: String, headers: Map<String, String>, mimeType: String?) {
        val key = buildString {
            append(url)
            headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                append('|').append(name).append('=').append(value)
            }
            append('|').append(mimeType)
        }
        if (player != null && playerKey == key) return

        releasePlayer()
        val sanitizedHeaders = PlayerMediaSourceFactory.sanitizeHeaders(headers)
        val resolvedMimeType = mimeType ?: PlayerMediaSourceFactory.inferMimeType(url, null)
        val dataSourceFactory = LoggingDataSourceFactory(
            PlayerPlaybackNetworking.createDataSourceFactory(context, sanitizedHeaders)
        )
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { resolvedMimeType?.let(::setMimeType) }
            .build()
        val mediaSource = when (resolvedMimeType) {
            MimeTypes.APPLICATION_M3U8 -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
            MimeTypes.APPLICATION_MPD -> DashMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
        }
        player = ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                    )
                    .setTargetBufferBytes(TARGET_BUFFER_BYTES)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
            .apply {
                setPlayWhenReady(false)
                // A thumbnail only needs the smallest video rendition and no
                // audio: far fewer bytes per seek and a faster first frame.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setForceLowestBitrate(true)
                    .build()
                addListener(listener)
                setVideoSurface(surface)
                setMediaSource(mediaSource)
                prepare()
            }
        playerKey = key
        Log.d(TAG, "hls player prepared mime=$resolvedMimeType")
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (pendingLatch == null) {
            drainImages()
            return
        }
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquire image failed: ${e.message}")
            null
        } ?: return
        val frame = imageToBitmap(image)
        image.close()
        if (frame != null) finish(frame)
    }

    private fun deliverPendingFrame() {
        if (pendingLatch == null) return
        imageReader?.let { onImageAvailable(it) }
    }

    /** Drops buffered frames so only post-seek images can be delivered. */
    private fun drainImages() {
        val reader = imageReader ?: return
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (e: Exception) {
                null
            } ?: return
            image.close()
        }
    }

    private fun finish(frame: Bitmap?) {
        val latch = pendingLatch ?: return
        val ref = pendingResult
        pendingLatch = null
        pendingResult = null
        ref?.set(frame)
        latch.countDown()
    }

    private fun releasePlayer() {
        player?.let { active ->
            runCatching { active.removeListener(listener) }
            runCatching { active.release() }
        }
        player = null
        playerKey = null
    }
    private fun imageToBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "unexpected image format=${image.format}")
        }
        return try {
            yuv420ToBitmap(image)
        } catch (e: Exception) {
            Log.w(TAG, "frame to bitmap failed: ${e.message}")
            null
        }
    }

    /** Converts a YUV_420_888 frame to an ARGB bitmap (BT.601 full range). */
    private fun yuv420ToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        val pixels = IntArray(width * height)
        var outIndex = 0
        for (row in 0 until height) {
            val yRowOffset = row * yRowStride
            val chromaRow = row / 2
            val uRowOffset = chromaRow * uRowStride
            val vRowOffset = chromaRow * vRowStride
            for (col in 0 until width) {
                val y = yBuffer.get(yRowOffset + col * yPixelStride).toInt() and 0xFF
                val chromaCol = col / 2
                val u = (uBuffer.get(uRowOffset + chromaCol * uPixelStride).toInt() and 0xFF) - 128
                val v = (vBuffer.get(vRowOffset + chromaCol * vPixelStride).toInt() and 0xFF) - 128

                val r = y + 1.402f * v
                val g = y - 0.344136f * u - 0.714136f * v
                val b = y + 1.772f * u

                pixels[outIndex++] = (0xFF.toInt() shl 24) or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or
                    (g.coerceIn(0f, 255f).toInt() shl 8) or
                    b.coerceIn(0f, 255f).toInt()
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}

@UnstableApi
private class LoggingDataSourceFactory(
    private val delegate: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = LoggingDataSource(delegate.createDataSource())
}

@UnstableApi
private class LoggingDataSource(
    private val delegate: DataSource
) : DataSource by delegate {

    override fun open(dataSpec: DataSpec): Long {
        return try {
            val bytes = delegate.open(dataSpec)
            Log.d("SeekThumbs", "hls open bytes=$bytes uri=${dataSpec.uri}")
            bytes
        } catch (e: Exception) {
            Log.w("SeekThumbs", "hls open FAILED uri=${dataSpec.uri} err=${e.javaClass.simpleName} ${e.message}")
            throw e
        }
    }
}
