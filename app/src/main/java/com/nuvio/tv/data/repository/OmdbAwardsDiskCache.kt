package com.nuvio.tv.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Persistent raw OMDb awards cache so reopening a title never re-resolves
 * ids or refetches OMDb. Stores the untranslated "Awards" string keyed by
 * every request identity that can reach the same title; localization happens
 * on read (see OmdbAwardsRepository.localizeAwards). Bounded: the eldest
 * entry is evicted past [maxEntries].
 */
internal class OmdbAwardsDiskCache(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES
) {
    /** raw is nullable only to survive hand-edited/corrupt JSON payloads. */
    internal data class Entry(
        val raw: String? = null,
        val fetchedAtMs: Long = 0L
    )

    companion object {
        internal const val MAX_ENTRIES = 500
        private const val TAG = "OmdbAwardsDiskCache"
    }

    private val gson = Gson()
    private var entries: MutableMap<String, Entry>? = null

    @Synchronized
    fun get(key: String): Entry? = ensureLoaded()[key]

    @Synchronized
    fun put(key: String, raw: String, fetchedAtMs: Long = System.currentTimeMillis()) {
        putAll(listOf(key), raw, fetchedAtMs)
    }

    @Synchronized
    fun putAll(keys: Collection<String>, raw: String, fetchedAtMs: Long = System.currentTimeMillis()) {
        if (keys.isEmpty() || raw.isBlank()) return
        val loaded = ensureLoaded()
        keys.forEach { key -> loaded[key] = Entry(raw = raw, fetchedAtMs = fetchedAtMs) }
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
            Log.w(TAG, "Failed to read awards cache: ${e.message}")
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
            Log.w(TAG, "Failed to write awards cache: ${e.message}")
        }
    }

    private fun newCache(): MutableMap<String, Entry> =
        object : LinkedHashMap<String, Entry>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
                size > maxEntries
        }
}
