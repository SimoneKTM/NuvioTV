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

    /**
     * Soglie di pressione del SISTEMA lette da /proc/meminfo.
     *
     * Tutti i kill LMK documentati seguono lo stesso schema: lo SwapFree della
     * Stick collassa da ~115 MB a 0 in ~80 s (lo zRAM si riempie delle nostre
     * pagine anonime) e lmkd sacrifica il processo in primo piano. A quel punto
     * l'avviso di lmkd (onTrimMemory livello 15) non serve più: serve liberare
     * PRIMA. I valori under-soglia di MemAvailable/SwapFree sono raggiungibili
     * in-process a costo zero e anticipano il kill di circa un minuto.
     */
    private const val HARD_PRESSURE_SWAP_KB = 40_000L
    private const val HARD_PRESSURE_AVAIL_KB = 90_000L
    private const val SOFT_PRESSURE_AVAIL_KB = 150_000L
    private const val SOFT_PRESSURE_SWAP_KB = 100_000L
    private const val RELEASE_AVAIL_KB = 250_000L
    private const val RELEASE_SWAP_KB = 180_000L

    /** Evita di liberare più di una volta quando il valore oscilla attorno alla soglia. */
    private const val RELIEF_MIN_INTERVAL_MS = 30_000L

    private enum class Pressure { NONE, SOFT, HARD }

    /**
     * Invocato (una volta per episodio) quando il sistema entra in pressione
     * critica: l'application libera trailer + cache immagini prima che lmkd
     * uccida il processo. Al callback NON vanno fatte chiamate bloccanti lunghe:
     * gira sul main handler del sampler.
     */
    @Volatile
    var onRelief: ((String) -> Unit)? = null

    @Volatile
    private var pressureState = Pressure.NONE

    @Volatile
    private var lastReliefAtMs = 0L

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

        // Stato di memoria del sistema: determina quando intervenire con il
        // sollievo (trim trailer + cache) prima che lmkd uccida il processo.
        var memAvailKb = -1L
        var swapFreeKb = -1L
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                val value = line.substringAfter(':').trim()
                when {
                    line.startsWith("MemAvailable:") -> memAvailKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                    line.startsWith("SwapFree:") -> swapFreeKb = value.removeSuffix("kB").trim().toLongOrNull() ?: -1L
                }
            }
        }
        evaluatePressure(memAvailKb, swapFreeKb, rssKb, swapKb)

        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024

        Log.i(
            TAG,
            "t=${elapsedS}s totalPss=${totalPssKb}kB javaHeap=${javaHeapKb}kB nativeHeap=${nativeHeapKb}kB " +
                "code=${codeKb}kB graphics=${graphicsKb}kB privOther=${privOtherKb}kB | " +
                "rtHeap=${heapUsedKb}/${rt.maxMemory() / 1024}kB nativeAlloc=${Debug.getNativeHeapAllocatedSize() / 1024}kB | " +
                "rss=${rssKb}kB swap=${swapKb}kB vmSize=${vmSizeKb}kB thr=$threads " +
                "| sysAvail=${memAvailKb}kB sysSwapFree=${swapFreeKb}kB"
        )
    }

    /**
     * Macchina a stati con isteresi su MemAvailable/SwapFree.
     *
     * - HARD  → zona di kill LMK (SwapFree < 40 MB oppure MemAvailable < 90 MB):
     *           si invoca [onRelief] per liberare subito trailer + cache.
     * - SOFT  → log diagnostico senza azioni (evita trim inutili).
     * - NONE  → condizioni di recupero: la prossima ripresa è di nuovo libera.
     * Valori di parse non validi (-1) mantengono lo stato corrente.
     */
    private fun evaluatePressure(memAvailKb: Long, swapFreeKb: Long, rssKb: Long, swapKb: Long) {
        val within = { value: Long, limit: Long -> value >= 0L && value <= limit }
        val newState = when {
            within(swapFreeKb, HARD_PRESSURE_SWAP_KB) || within(memAvailKb, HARD_PRESSURE_AVAIL_KB) -> Pressure.HARD
            within(memAvailKb, SOFT_PRESSURE_AVAIL_KB) && within(swapFreeKb, SOFT_PRESSURE_SWAP_KB) -> Pressure.SOFT
            memAvailKb > RELEASE_AVAIL_KB && swapFreeKb > RELEASE_SWAP_KB -> Pressure.NONE
            else -> pressureState
        }
        val old = pressureState
        pressureState = newState

        when {
            newState == Pressure.HARD && old != Pressure.HARD -> {
                Log.w(
                    TAG,
                    "PRESSURE HARD avail=${memAvailKb}kB swapFree=${swapFreeKb}kB " +
                        "rss=${rssKb}kB swap=${swapKb}kB — releasing trailer+cache"
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReliefAtMs >= RELIEF_MIN_INTERVAL_MS) {
                    lastReliefAtMs = now
                    onRelief?.let { relief ->
                        runCatching { relief("system-hard avail=${memAvailKb}kB swapFree=${swapFreeKb}kB") }
                            .onFailure { Log.w(TAG, "relief callback failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
            newState == Pressure.SOFT && old == Pressure.NONE -> {
                Log.w(TAG, "PRESSURE SOFT avail=${memAvailKb}kB swapFree=${swapFreeKb}kB rss=${rssKb}kB")
            }
            newState == Pressure.NONE && old != Pressure.NONE -> {
                Log.i(TAG, "pressure released, avail=${memAvailKb}kB swapFree=${swapFreeKb}kB")
            }
        }
    }
}
