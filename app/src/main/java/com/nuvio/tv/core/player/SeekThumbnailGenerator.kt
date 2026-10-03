package com.nuvio.tv.core.player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts video frames from the stream currently being played so the player
 * can render a Netflix-style filmstrip while the user scrubs the seek bar.
 *
 * Progressive streams are read with [MediaMetadataRetriever] against the
 * playback URL (no DRM is used by the app). Adaptive streams (HLS/DASH) are
 * handled by [HlsFrameExtractor]. Results are cached in memory; streams that
 * consistently fail are marked unsupported and the filmstrip stays hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SeekThumbnailGenerator @Inject constructor(
    private val hlsExtractor: HlsFrameExtractor
) {

    companion object {
        private const val TAG = "SeekThumbs"
        private const val MAX_CACHE_KB = 8 * 1024
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val KEEP_WARM_MS = 30_000L
        private val UNSUPPORTED_SCHEMES = listOf("blob:", "rtmp://", "rtsp://", "file://")
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(2)
    )
    private val requestsMutex = Mutex()
    private val hlsMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private var releaseJob: Job? = null

    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUrl: String? = null

    /** URLs the classic retriever cannot read; the adaptive extractor may still handle them. */
    private val retrieverFailedUrls: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var failedUrl: String? = null
    private var consecutiveFailures = 0

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1) / 1024
    }

    /** False for streams known to be unable to yield frames (or already failed). */
    fun isSupported(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.substringBefore('?').lowercase()
        if (UNSUPPORTED_SCHEMES.any { clean.startsWith(it) }) return false
        return failedUrl != url
    }

    /**
     * Returns the frame closest to [positionMs] for [url], or null when the
     * stream cannot provide one. Concurrent requests for the same position are
     * deduplicated and results are cached in memory.
     */
    suspend fun getFrame(
        url: String,
        headers: Map<String, String>,
        positionMs: Long
    ): Bitmap? {
        if (!isSupported(url)) return null
        releaseJob?.cancel()
        releaseJob = null

        val key = "$url#${positionMs.coerceAtLeast(0L)}"
        cache.get(key)?.let { return it }

        val deferred = requestsMutex.withLock {
            inFlight[key] ?: run {
                val created = scope.async(start = CoroutineStart.LAZY) {
                    val frame = extractFrame(url, headers, positionMs)
                    Log.d(TAG, "extract pos=$positionMs ok=${frame != null} host=${url.safeHostForLog()}")
                    frame?.let { cache.put(key, it) }
                    requestsMutex.withLock { inFlight.remove(key) }
                    frame
                }
                inFlight[key] = created
                created.start()
                created
            }
        }
        return deferred.await()
    }

    /** Frees the underlying retriever (and its connection to the stream). */
    fun release() {
        synchronized(retrieverLock) { releaseRetrieverLocked() }
        hlsExtractor.release()
    }

    /**
     * Releases the extractors after [delayMs] of inactivity. Keeping them warm
     * briefly avoids re-preparing the hidden player every time the controls are
     * hidden and shown again during a scrub session.
     */
    fun scheduleRelease(delayMs: Long = KEEP_WARM_MS) {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(delayMs)
            release()
        }
    }

    private suspend fun extractFrame(
        url: String,
        headers: Map<String, String>,
        positionMs: Long
    ): Bitmap? {
        if (failedUrl == url) return null
        if (isManifestUrl(url)) {
            val frame = extractAdaptiveFrame(url, headers, positionMs, manifestMimeType(url))
            Log.d(TAG, "extract adaptive pos=$positionMs ok=${frame != null}")
            if (frame != null) {
                consecutiveFailures = 0
            } else {
                noteFailure(url)
            }
            return frame
        }
        val retrieverFrame = if (url in retrieverFailedUrls) {
            null
        } else {
            extractRetrieverFrame(url, headers, positionMs)
        }
        if (retrieverFrame != null) return retrieverFrame

        val adaptiveFrame = extractAdaptiveFrame(url, headers, positionMs, null)
        if (adaptiveFrame != null) {
            Log.d(TAG, "extract fallback ok=true pos=$positionMs")
            consecutiveFailures = 0
            return adaptiveFrame
        }
        noteFailure(url)
        return null
    }

    private fun extractRetrieverFrame(
        url: String,
        headers: Map<String, String>,
        positionMs: Long
    ): Bitmap? {
        synchronized(retrieverLock) {
            if (failedUrl == url) return null
            val active = ensureRetriever(url, headers) ?: return null
            return try {
                val frame = active.getFrameAtTime(
                    positionMs.coerceAtLeast(0L) * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    consecutiveFailures = 0
                    frame
                } else {
                    // Keep the URL alive: the adaptive extractor may still read it.
                    retrieverFailedUrls.add(url)
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "getFrameAtTime failed: ${e.message}")
                retrieverFailedUrls.add(url)
                null
            }
        }
    }

    private suspend fun extractAdaptiveFrame(
        url: String,
        headers: Map<String, String>,
        positionMs: Long,
        mimeType: String?
    ): Bitmap? = hlsMutex.withLock {
        if (failedUrl == url) return@withLock null
        runCatching {
            hlsExtractor.getFrame(url, headers, positionMs, mimeType)
        }.onFailure { error ->
            Log.w(TAG, "adaptive extract failed: ${error.message}")
        }.getOrNull()
    }

    private fun isManifestUrl(url: String): Boolean {
        val clean = url.substringBefore('?').lowercase()
        return clean.endsWith(".m3u8") || clean.endsWith(".m3u") ||
            clean.endsWith(".mpd") || clean.endsWith(".ism")
    }

    private fun manifestMimeType(url: String): String? {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") || clean.endsWith(".m3u") -> "application/vnd.apple.mpegurl"
            clean.endsWith(".mpd") -> "application/dash+xml"
            else -> null
        }
    }

    private fun ensureRetriever(
        url: String,
        headers: Map<String, String>
    ): MediaMetadataRetriever? {
        if (retrieverUrl == url && retriever != null) return retriever
        releaseRetrieverLocked()
        return try {
            val created = MediaMetadataRetriever()
            @Suppress("DEPRECATION")
            created.setDataSource(url, headers)
            retriever = created
            retrieverUrl = url
            consecutiveFailures = 0
            created
        } catch (e: Exception) {
            Log.w(TAG, "retriever init failed: ${e.message}")
            retrieverFailedUrls.add(url)
            null
        }
    }

    private fun noteFailure(url: String) {
        consecutiveFailures++
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            failedUrl = url
        }
    }

    private fun releaseRetrieverLocked() {
        retriever?.let { active -> runCatching { active.release() } }
        retriever = null
        retrieverUrl = null
        consecutiveFailures = 0
    }

    private fun String.safeHostForLog(): String =
        runCatching { android.net.Uri.parse(this).host ?: take(60) }.getOrDefault("parse-error")
}
