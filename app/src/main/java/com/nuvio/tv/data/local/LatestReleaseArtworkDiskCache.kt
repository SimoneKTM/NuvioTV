package com.nuvio.tv.data.local

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Cache persistente dell'artwork di "Ultime Uscite" (logo/poster/sfondo/
 * descrizione) keyed per content id. Senza di essa ogni riavvio
 * dell'app dovrebbe rinterrogare TMDB per ~55 titoli prima che la riga
 * diventi visibile (decine di secondi su una Fire TV Stick); con la
 * cache la prima emissione del calendario dipinge la riga
 * istantaneamente e l'arricchimento online la aggiorna in background.
 *
 * Bounded all'antica OmdbAwardsDiskCache: superato [maxEntries] viene
 * evictato l'entry più vecchia (LinkedHashMap insertion order).
 */
internal class LatestReleaseArtworkDiskCache(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES
) {
    internal data class Entry(
        val logo: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val description: String? = null,
        val fetchedAtMs: Long = 0L
    )

    companion object {
        internal const val MAX_ENTRIES = 400
        private const val TAG = "LatestReleaseArtworkCache"
    }

    private val gson = Gson()
    private var entries: MutableMap<String, Entry>? = null

    @Synchronized
    fun get(key: String): Entry? = ensureLoaded()[key]

    @Synchronized
    fun put(key: String, entry: Entry) {
        if (key.isBlank()) return
        val loaded = ensureLoaded()
        loaded[key] = entry
        persist(loaded)
    }

    private fun ensureLoaded(): MutableMap<String, Entry> {
        entries?.let { return it }
        val loaded = try {
            if (file.exists()) {
                val parsed: Map<String, Entry>? = gson.fromJson(
                    file.readText(),
                    object : TypeToken<Map<String, Entry>>() {}.type
                )
                newCache().apply { parsed?.forEach { (key, value) -> if (value != null) put(key, value) } }
            } else {
                newCache()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read artwork cache: ${e.message}")
            newCache()
        }
        entries = loaded
        return loaded
    }

    private fun persist(snapshot: Map<String, Entry>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(snapshot))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write artwork cache: ${e.message}")
        }
    }

    private fun newCache(): MutableMap<String, Entry> =
        object : LinkedHashMap<String, Entry>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > maxEntries
        }
}
