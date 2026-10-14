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
     * Campionamento rapido (3 s) quando si è in pressione o la RSS sta
     * fugendo: l'ondata del kill delle 12:46 ha fatto +220 MB in 6 secondi —
     * con il campione ogni 10 s il processo moriva tra due campioni.
     * In modalità rapida si saltano Debug.getMemoryInfo (il passo più caro,
     * eseguito sul main handler): basta lo stato letto da /proc.
     */
    private const val FAST_INTERVAL_MS = 3_000L

    /** Delta di RSS tra due campioni che segnala una fuga in corso. */
    private const val RUNAWAY_RSS_DELTA_KB = 100_000L // 100 MB

    /**
     * La fuga RSS vale solo su RSS già alte: la crescita normale di avvio
     * (processo fresco: 30 -> 140 MB in pochi secondi) NON è una fuga.
     * Senza questo gate il governor entrava in HARD a ogni avvio app.
     */
    private const val RUNAWAY_MIN_RSS_KB = 300_000L

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
    // Per uscire da HARD/NONE servono ENTRAMBE le soglie, ma calibrate
    // sulla realtà di queste Stick: a riposo vivono a ~180-260MB
    // disponibili. Le soglie vecchie (>250MB/180MB) non si raggiungevano
    // quasi mai → lo stato restava piantato e tutto ciò che è gatellato
    // dalla pressione (arrivo CW, enrichment, trailer) restava in attesa.
    private const val RELEASE_AVAIL_KB = 150_000L
    private const val RELEASE_SWAP_KB = 100_000L

    /**
     * Pressione AUTO-imposta: la RSS del processo. Il kill delle 23:01 è
     * arrivato con rss=511 MB mentre il sistema era borderline — guardare
     * solo il sistema non basta: su una Stick da 1 GB il processo deve
     * tenere d'occhio anche SE STESSO. 380 MB di RSS occupano già ~40% della
     * RAM fisica; la soglia vale solo sulle classi di RAM basse, dove quei
     * MB sono critici (su TV da 2+ GB 380 MB sono normali).
     */
    private const val HARD_SELF_RSS_KB = 380_000L
    private const val LOW_RAM_TOTAL_KB = 1_677_721L // 1.6 GB (classe gentle)

    /** Evita di liberare più di una volta quando il valore oscilla attorno alla soglia. */
    private const val RELIEF_MIN_INTERVAL_MS = 30_000L

    /**
     * Riedizione del sollievo mentre lo stato resta HARD: la prima
     * liberazione non basta se l'app continua a crescere (scroll rapido +
     * trailer). Finché dura la pressione si ripete al massimo ogni 45 s.
     */
    private const val RELIEF_REPEAT_MS = 45_000L

    private enum class Pressure { NONE, SOFT, HARD }

    /**
     * Invocato (una volta per episodio) quando il sistema entra in pressione
     * critica: l'application libera trailer + cache immagini prima che lmkd
     * uccida il processo. Al callback NON vanno fatte chiamate bloccanti lunghe:
     * gira sul main handler del sampler.
     */
    @Volatile
    var onRelief: ((String) -> Unit)? = null

    /**
     * Vero quando il sistema è in zona di kill (stato HARD). Usato da trailer
     * pool e preloader per NON aggiungere allocazioni mentre l'LMK sta per
     * colpire: sotto pressione la home mostra lo sfondo statico invece di
     * avviare decoder, e il preload viene differito.
     */
    @Volatile
    var underPressure: Boolean = false
        private set

    @Volatile
    private var pressureState = Pressure.NONE

    @Volatile
    private var lastReliefAtMs = 0L

    /** RAM fisica del dispositivo (letta una volta in [start]). */
    @Volatile
    private var totalMemKb = -1L

    /** RSS del campione precedente — base per rilevare fughe tra due letture. */
    @Volatile
    private var lastRssKb = -1L

    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMs = 0L

    @Volatile
    private var running = false

    private val sampler = object : Runnable {
        override fun run() {
            if (!running) return
            val nextDelayMs = runCatching { sampleOnce() }
                .onFailure { Log.w(TAG, "sample failed: ${it.javaClass.simpleName}") }
                .getOrDefault(INTERVAL_MS)
            handler.postDelayed(this, nextDelayMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        startedAtMs = android.os.SystemClock.elapsedRealtime()
        runCatching {
            File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("MemTotal:")) {
                    totalMemKb = line.substringAfter(':').removeSuffix("kB").trim().toLongOrNull() ?: -1L
                }
            }
        }
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

    /** Legge il prossimo intervallo: 3 s in emergenza, 10 s a regime. */
    private fun sampleOnce(): Long {
        val elapsedS = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000

        // 1) Lettura economica da /proc: basta per decidere se c'è emergenza
        //    (pressione di sistema o fuga di RSS) senza toccare Debug API.
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

        // 2) Fuga RSS: tra due campioni il processo è cresciuto di troppo
        //    (scroll veloce che decodifica senza sosta, trailer + modelli).
        //    Anche se il sistema è in salute, questa curva porta al kill.
        //    Vale solo su RSS già alte (vedi RUNAWAY_MIN_RSS_KB).
        val runaway = rssKb >= RUNAWAY_MIN_RSS_KB &&
            lastRssKb >= 0L && rssKb - lastRssKb >= RUNAWAY_RSS_DELTA_KB
        if (rssKb >= 0L) lastRssKb = rssKb
        evaluatePressure(memAvailKb, swapFreeKb, rssKb, swapKb, runaway)

        // 3) Modalità rapida sotto pressione/fuga: saltiamo il breakdown PSS
        //    (Debug.getMemoryInfo sul main handler) — serve solo a log.
        val fast = pressureState != Pressure.NONE || runaway
        var totalPssKb = -1L
        var javaHeapKb = -1L
        var nativeHeapKb = -1L
        var codeKb = -1L
        var graphicsKb = -1L
        var privOtherKb = -1L
        if (!fast) {
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            fun stat(key: String): Long =
                runCatching { mi.getMemoryStat(key).toLongOrNull() ?: -1L }.getOrDefault(-1L)
            totalPssKb = stat("summary.total-pss")
            javaHeapKb = stat("summary.java-heap")
            nativeHeapKb = stat("summary.native-heap")
            codeKb = stat("summary.code")
            graphicsKb = stat("summary.graphics")
            privOtherKb = stat("summary.private-other")
        }

        val rt = Runtime.getRuntime()
        val heapUsedKb = (rt.totalMemory() - rt.freeMemory()) / 1024

        Log.i(
            TAG,
            "t=${elapsedS}s${if (fast) " fast" else ""} totalPss=${totalPssKb}kB javaHeap=${javaHeapKb}kB nativeHeap=${nativeHeapKb}kB " +
                "code=${codeKb}kB graphics=${graphicsKb}kB privOther=${privOtherKb}kB | " +
                "rtHeap=${heapUsedKb}/${rt.maxMemory() / 1024}kB nativeAlloc=${Debug.getNativeHeapAllocatedSize() / 1024}kB | " +
                "rss=${rssKb}kB swap=${swapKb}kB vmSize=${vmSizeKb}kB thr=$threads " +
                "| sysAvail=${memAvailKb}kB sysSwapFree=${swapFreeKb}kB"
        )
        return if (fast) FAST_INTERVAL_MS else INTERVAL_MS
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
    private fun evaluatePressure(memAvailKb: Long, swapFreeKb: Long, rssKb: Long, swapKb: Long, runaway: Boolean = false) {
        val within = { value: Long, limit: Long -> value >= 0L && value <= limit }
        // Pressione auto-imposta sulla nostra RSS (solo classi RAM basse):
        // se il processo supera il tetto, siamo noi il problema, non il sistema.
        // La fuga RSS (delta >= 100 MB tra due campioni) vale PER TUTTI i
        // dispositivi: la curva del kill delle 12:46 (+220 MB in 6 s) uccide
        // prima che il sistema abbia tempo di avvisare.
        val selfRssHard = totalMemKb in 1L until LOW_RAM_TOTAL_KB && rssKb >= HARD_SELF_RSS_KB
        val newState = when {
            within(swapFreeKb, HARD_PRESSURE_SWAP_KB) || within(memAvailKb, HARD_PRESSURE_AVAIL_KB) || selfRssHard || runaway -> Pressure.HARD
            within(memAvailKb, SOFT_PRESSURE_AVAIL_KB) && within(swapFreeKb, SOFT_PRESSURE_SWAP_KB) -> Pressure.SOFT
            memAvailKb > RELEASE_AVAIL_KB && swapFreeKb > RELEASE_SWAP_KB -> Pressure.NONE
            else -> pressureState
        }
        val old = pressureState
        pressureState = newState
        underPressure = newState == Pressure.HARD

        when {
            newState == Pressure.HARD && old != Pressure.HARD -> {
                Log.w(
                    TAG,
                    "PRESSURE HARD avail=${memAvailKb}kB swapFree=${swapFreeKb}kB " +
                        "rss=${rssKb}kB swap=${swapKb}kB selfRss=$selfRssHard runaway=$runaway — releasing trailer+cache"
                )
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReliefAtMs >= RELIEF_MIN_INTERVAL_MS) {
                    lastReliefAtMs = now
                    onRelief?.let { relief ->
                        runCatching { relief("system-hard avail=${memAvailKb}kB swapFree=${swapFreeKb}kB rss=${rssKb}kB") }
                            .onFailure { Log.w(TAG, "relief callback failed: ${it.javaClass.simpleName}") }
                    }
                }
            }
            newState == Pressure.HARD -> {
                // Stato HARD sostenuto: l'app è ancora viva e sta ancora
                // crescendo — si ripete il sollievo ogni RELIEF_REPEAT_MS
                // (il debounce interno impedisce di eccedere).
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastReliefAtMs >= RELIEF_REPEAT_MS) {
                    Log.w(TAG, "PRESSURE HARD sostenuta rss=${rssKb}kB avail=${memAvailKb}kB — repeat relief")
                    lastReliefAtMs = now
                    onRelief?.let { relief ->
                        runCatching { relief("system-hard-sustained rss=${rssKb}kB avail=${memAvailKb}kB") }
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
