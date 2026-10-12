package com.nuvio.tv.core.build

import android.util.Log
import java.io.File

/**
 * RAM totale del dispositivo, letta senza Context da /proc/meminfo.
 *
 * Sulle Stick da ~900 MB il decoder video del trailer + il buffer di ExoPlayer
 * (pur azzerato a 8MB) spingono il processo oltre il budget di lmkd: il kill
 * arriva come SIGKILL senza stack trace, quindi l'unica difesa è non avviare
 * affatto il player quando la macchina non ha RAM a sufficienza.
 *
 * Sotto soglia i trailer in-app vengono disabilitati a livello di policy e
 * l'hero della home usa solo l'immagine statica.
 */
internal object DeviceMemoryPolicy {

    /**
     * Soglia in byte (1.5 GiB): dispositivi con RAM totale inferiore non
     * avviano ExoPlayer per i trailer. Una Stick HD dichiara ~900 MB,
     * un device da 2 GB resta sopra e continua a riprodurli.
     */
    private const val LOW_MEMORY_THRESHOLD_BYTES = 1_610_612_736L

    private const val TAG = "DeviceMemoryPolicy"

    /** true = poca RAM a disposizione, i trailer in-app non devono partire. */
    val isLowMemoryDevice: Boolean by lazy {
        val totalBytes = readTotalMemBytes()
        val lowMemory = totalBytes == null || totalBytes < LOW_MEMORY_THRESHOLD_BYTES
        Log.i(
            TAG,
            "MemTotal=${totalBytes ?: "unknown"} bytes, " +
                "soglia=$LOW_MEMORY_THRESHOLD_BYTES bytes -> isLowMemoryDevice=$lowMemory"
        )
        lowMemory
    }

    /**
     * MemTotal in byte, oppure null se /proc/meminfo non è leggibile
     * (in tal caso si assume il caso prudente: poca RAM, niente trailer).
     */
    private fun readTotalMemBytes(): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }
    }.getOrNull()
}
