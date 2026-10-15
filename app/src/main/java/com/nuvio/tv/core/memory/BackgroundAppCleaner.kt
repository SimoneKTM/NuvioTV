package com.nuvio.tv.core.memory

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Chiusura immediata delle app appena lasciate dall'utente.
 *
 * Su una Fire TV Stick da 1GB ogni app abbandonata resta in RAM in stato
 * "cached" e frega memoria alla prossima app che si apre. Questo cleaner la
 * uccide subito con killBackgroundProcesses, che tocca SOLO processi già in
 * background: app in primo piano, visibili o con servizi attivi (es. musica
 * in riproduzione) non vengono mai toccate — lo decide il sistema.
 *
 * Due modalità:
 *  1. Watcher (serve l'appop GET_USAGE_STATS, concesso una tantum via adb:
 *     `appops set com.nuvio.tv GET_USAGE_STATS allow`): osserva i passaggi di
 *     primo piano e, appena un'app ne esce, la uccide quando è cached.
 *  2. Pulizia secca "tutto cached", invocata all'apertura e alla chiusura di
 *     Nuvio: funziona anche senza l'appop.
 */
object BackgroundAppCleaner {
    private const val TAG = "BgAppCleaner"

    /** Quanto spesso si controlla chi è in primo piano. */
    private const val POLL_MS = 1200L

    /** Attesa dopo l'uscita di un'app prima di ucciderla (deve finire di
     *  fermarsi e scendere a stato cached). */
    private const val GRACE_MS = 2500L

    /** Gate anti-spam della pulizia secca. */
    private const val CLEAN_GATE_MS = 2000L

    /** Appop di UsageStatsManager (API 19+; la costante statica è compilata
     *  inline quindi la stringa va bene su ogni versione). */
    private const val USAGE_STATS_OP = "android:get_usage_stats"

    private var started = false
    private var lastCleanAtMs = 0L

    /** Avvia il watcher dei cambi di primo piano (una sola volta). */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val granted = hasUsageStats(appContext)
        Log.i(TAG, "watcher avviato usageStatsConcesso=$granted")
        if (!granted) return

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val event = UsageEvents.Event()
            var currentForeground: String? = null
            var pendingKill: Pair<String, Long>? = null
            while (isActive) {
                delay(POLL_MS)
                runCatching {
                    val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE)
                        as? UsageStatsManager ?: return@runCatching
                    val now = System.currentTimeMillis()
                    val events = usm.queryEvents(now - POLL_MS * 3, now)
                    var lastResumed: String? = null
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                            lastResumed = event.packageName
                        }
                    }
                    if (lastResumed != null && lastResumed != currentForeground) {
                        currentForeground?.let { previous -> pendingKill = previous to now }
                        currentForeground = lastResumed
                    }
                    pendingKill?.let { (pkg, sinceMs) ->
                        if (pkg != currentForeground && now - sinceMs >= GRACE_MS) {
                            pendingKill = null
                            killIfCached(appContext, pkg)
                        }
                    }
                }.onFailure { Log.w(TAG, "poll fallito: ${it.message}") }
            }
        }
    }

    /**
     * Uccide tutti i processi cached altrui. Da chiamare all'apertura (primo
     * passo subito, secondo passo oltre la grazia, così chi sta appena
     * sfumando in background viene preso anche lui).
     */
    fun cleanOnNuvioLaunch(context: Context) {
        cleanAllCached(context)
        Handler(Looper.getMainLooper()).postDelayed({ cleanAllCached(context) }, GRACE_MS + 100L)
    }

    /** Uccide tutti i processi cached altrui, con gate anti-spam. */
    fun cleanAllCached(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCleanAtMs < CLEAN_GATE_MS) return
        lastCleanAtMs = now
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val myPid = Process.myPid()
        val cachedPkgs = am.runningAppProcesses
            .orEmpty()
            .filter { it.pid != myPid && it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }
            .flatMap { it.pkgList?.toList().orEmpty() }
            .filter { it != context.packageName }
            .distinct()
        if (cachedPkgs.isEmpty()) return
        cachedPkgs.forEach { pkg -> runCatching { am.killBackgroundProcesses(pkg) } }
        Log.i(TAG, "cleanAllCached: ${cachedPkgs.size} pacchetti cached uccisi")
    }

    private fun killIfCached(context: Context, pkg: String) {
        if (pkg == context.packageName) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val procs = am.runningAppProcesses.orEmpty().filter { it.pkgList?.contains(pkg) == true }
        if (procs.isEmpty()) return
        val stillActive = procs.any { it.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED }
        if (stillActive) {
            Log.i(TAG, "$pkg ancora attivo (visibile o con servizi): non tocco")
            return
        }
        runCatching { am.killBackgroundProcesses(pkg) }
        Log.i(TAG, "$pkg chiuso (era cached)")
    }

    private fun hasUsageStats(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        appOps.checkOpNoThrow(USAGE_STATS_OP, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
