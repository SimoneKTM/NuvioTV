package com.nuvio.tv.core.diagnostics

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Lightweight in-process memory telemetry.
 *
 * The Fire TV Stick kills NuvioTV via LMK once the process reaches ~650-750 MB,
 * but `dumpsys meminfo` needs a binder round-trip into the app: during the
 * allocation bursts that precede the kill the main thread is so busy that
 * dumpsys times out, so the category breakdown (Java vs Native vs Graphics)
 * was never capturable from outside.
 *
 * This sampler runs inside the process on the main handler, reads
 * [Debug.getMemoryInfo] (process-local libmemtrack walk, no binder) plus
 * /proc/self/status, and emits ONE compact line every [INTERVAL_MS] via Log.i
 * (Log.i survives in release builds; Log.d does not). A PC-side logcat capture
 * then preserves the full memory curve across kills.
 */
object MemoryWatch {
    private const val TAG = "MemWatch"
    private const val INTERVAL_MS = 10_000L
    private const val FIRST_DELAY_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    @Volatile
    private var running = false

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { sampleOnce() }
                .onFailure { Log.w(TAG, "sample failed: ${it.javaClass.simpleName}") }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        val rt = Runtime.getRuntime()
        Log.i(
            TAG,
            "watch started heapMax=${rt.maxMemory() / 1024}kB " +
                "heapTotal=${rt.totalMemory() / 1024}kB nativeHeap=${Debug.getNativeHeapAllocatedSize() / 1024}kB"
        )
        handler.postDelayed(sampler, FIRST_DELAY_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(sampler)
    }

    private fun sampleOnce() {
        val elapsedS = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000

        // Full category PSS breakdown (same stats printed by dumpsys "App Summary").
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        fun stat(key: String): Long =
            runCatching { mi.getMemoryStat(key).toLongOrNull() ?: -1L }.getOrDefault(-1L)

        val totalPssKb = stat("summary.total-pss")
        val javaHeapKb = stat("summary.java-heap")
        val nativeHeapKb = stat("summary.native-heap")
        val codeKb = stat("summary.code")
        val graphicsKb = stat("summary.graphics")
        val privOtherKb = stat("summary.private-other")

        // Raw process view: RSS includes swapped-in anon; VmSwap shows how much of
        // our anon footprint currently sits in zRAM (dumpsys PSS misses it).
        var rssKb = -1L
        var swapKb = -1L
        var vmSizeKb = -1L
        var threads = -1
        runCatching {
            File("/proc/self/status").forEachLine { line ->
                val value = line.substringAfter(':', "").trim()
                when {
                    line.startsWith("VmRSS:") -> rssKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("VmSwap:") -> swapKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("VmSize:") -> vmSizeKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("Threads:") -> threads = value.trim().toIntOrNull() ?: -1
                }
            }
        }

        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024

        Log.i(
            TAG,
            "t=${elapsedS}s totalPss=${totalPssKb}kB javaHeap=${javaHeapKb}kB nativeHeap=${nativeHeapKb}kB " +
                "code=${codeKb}kB graphics=${graphicsKb}kB privOther=${privOtherKb}kB | " +
                "rtHeap=${heapUsedKb}/${rt.maxMemory() / 1024}kB nativeAlloc=${Debug.getNativeHeapAllocatedSize() / 1024}kB | " +
                "rss=${rssKb}kB swap=${swapKb}kB vmSize=${vmSizeKb}kB thr=$threads"
        )
    }
}
