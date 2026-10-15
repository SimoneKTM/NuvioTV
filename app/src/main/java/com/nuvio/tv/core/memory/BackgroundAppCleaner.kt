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

    /** Intervallo tra un tentativo di kill e l'app successiva: subito dopo
     *  il cambio di app il processo resta per un po' in stato "previous" o
     *  perceptible (adj 200-700) e killBackgroundProcesses non lo tocca;
     *  appena scende a stato killable l'unico modo di coglierlo è
     *  riprovare. */
    private const val KILL_RETRY_MS = 8_000L

    /** Quanti tentativi per l'app lasciata (≈2 minuti: oltre, viene presa
     *  dalla pulizia secca al prossimo cambio di app). */
    private const val KILL_MAX_ATTEMPTS = 15

    /** Gate anti-spam della pulizia secca. */
    private const val CLEAN_GATE_MS = 2000L

    /** Finestra "appena usate": uccidiamo chi è stato in primo piano negli ultimi X. */
    private const val RECENT_WINDOW_MS = 15 * 60 * 1000L

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
            var pendingKill: Triple<String, Long, Int>? = null
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
                        currentForeground?.let { previous ->
                            pendingKill = Triple(previous, now + GRACE_MS, 0)
                        }
                        currentForeground = lastResumed
                        // Ogni passaggio di primo piano è il momento in cui le
                        // app più vecchie scendono a cached: fallo ripulire
                        // (il gate anti-spam di cleanAllCached limita il costo).
                        cleanAllCached(appContext)
                    }
                    pendingKill?.let { (pkg, nextAttemptMs, attempts) ->
                        if (pkg == currentForeground) {
                            pendingKill = null
                            return@let
                        }
                        if (now < nextAttemptMs) return@let
                        killIfCached(appContext, pkg)
                        // Il kill è un no-op finché il processo non è davvero
                        // killable (stato previous/perceptible per qualche
                        // secondo): riprova a intervalli finché non sparisce
                        // o torna in primo piano.
                        pendingKill = if (attempts + 1 >= KILL_MAX_ATTEMPTS) {
                            null
                        } else {
                            Triple(pkg, now + KILL_RETRY_MS, attempts + 1)
                        }
                    }
                }.onFailure { Log.w(TAG, "poll fallito: ${it.message}") }
            }
        }
    }

    /**
     * Uccide le app appena lasciate in background. Da chiamare all'apertura
     * (primo passo subito, secondo passo oltre la grazia, così chi sta appena
     * sfumando in background viene preso anche lui).
     *
     * NOTA: su Android moderno getRunningAppProcesses restituisce SOLO i
     * processi dell'app chiamante, quindi l'enumerazione "tutto cached" non
     * vede nulla. killBackgroundProcesses è comunque safe di per sé (il
     * sistema uccide solo processi già in stato cached): si applica alle
     * app usate di recente, che sono proprio quelle appena lasciate.
     */
    fun cleanOnNuvioLaunch(context: Context) {
        cleanAllCached(context)
        Handler(Looper.getMainLooper()).postDelayed({ cleanAllCached(context) }, GRACE_MS + 100L)
    }

    /** Uccide le app usate di recente (ultimi [RECENT_WINDOW_MS]), con gate anti-spam. */
    fun cleanAllCached(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCleanAtMs < CLEAN_GATE_MS) return
        lastCleanAtMs = now
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val recentPkgs = recentPackages(context)
        if (recentPkgs.isEmpty()) return
        recentPkgs.forEach { pkg -> runCatching { am.killBackgroundProcesses(pkg) } }
        Log.i(TAG, "cleanAllCached: kill richiesto su ${recentPkgs.size} app usate di recente")
    }

    private fun killIfCached(context: Context, pkg: String) {
        if (pkg == context.packageName) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        // Nessun filtro importance: su Android moderno l'elenco dei processi
        // altrui non è visibile all'app, quindi il filtro vedeva lista vuota
        // e non uccideva MAI niente. killBackgroundProcesses tocca solo i
        // cached (lo decide il sistema): è sicuro chiamarlo direttamente.
        runCatching { am.killBackgroundProcesses(pkg) }
        Log.i(TAG, "$pkg kill richiesto (il sistema uccide solo i cached)")
    }

    /** App usate di recente (esclusa se stessa), via UsageStats. */
    private fun recentPackages(context: Context): List<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - RECENT_WINDOW_MS, now)
                .orEmpty()
                .filter { it.lastTimeUsed >= now - RECENT_WINDOW_MS }
                .mapNotNull { it.packageName }
                .filter { it != context.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun hasUsageStats(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        appOps.checkOpNoThrow(USAGE_STATS_OP, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
