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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts video frames from the stream currently being played so the player
 * can render a Netflix-style filmstrip while the user scrubs the seek bar.
 *
 * Frames are read with [MediaMetadataRetriever] against the playback URL (no
 * DRM is used by the app) and cached in memory. Streams that cannot provide
 * frames (HLS/DASH manifests, servers without range support) are marked as
 * unsupported after a few failures and the filmstrip simply stays hidden.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SeekThumbnailGenerator @Inject constructor() {

    companion object {
        private const val TAG = "SeekThumbs"
        private const val MAX_CACHE_KB = 8 * 1024
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private val UNSUPPORTED_SUFFIXES = listOf(".m3u8", ".mpd")
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(2)
    )
    private val requestsMutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()

    private val retrieverLock = Any()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUrl: String? = null

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
        if (UNSUPPORTED_SUFFIXES.any { clean.endsWith(it) }) return false
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

        val key = "$url#${positionMs.coerceAtLeast(0L)}"
        cache.get(key)?.let { return it }

        val deferred = requestsMutex.withLock {
            inFlight[key] ?: run {
                val created = scope.async(start = CoroutineStart.LAZY) {
                    val frame = extractFrame(url, headers, positionMs)
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
    }

    private fun extractFrame(
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
                    noteFailure(url)
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "getFrameAtTime failed: ${e.message}")
                noteFailure(url)
                null
            }
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
            failedUrl = url
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
}
