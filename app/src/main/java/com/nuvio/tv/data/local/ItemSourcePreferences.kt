package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-item memory of which addon produced a title's meta, the Search/Calendar
 * counterpart of the library's SavedLibraryItem.addonBaseUrl.
 *
 * Details opened from Home/Anime (or anywhere with a route addon) write the
 * winning addon here; Search/Discover/Calendar details read it first so the
 * same title resolves with the same addon on every screen (4 seasons like in
 * Home, not the generic 1 from a different card source).
 *
 * Entries are keyed by every id alias of the title (route id, lookup id,
 * meta id such as "tt13196080", raw numeric id) so "tmdb:13196080" from Home,
 * "tt13196080" from Search and "trakt_tv_*" from Calendar all hit the same
 * memory.
 */
@Singleton
class ItemSourcePreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "ItemSource"
        // v2: v1 recorded every resolution (including pool-race winners from
        // Search itself), which poisoned the memory — start from a clean slate.
        private const val FEATURE = "item_source_memory_v2"
        internal const val MAX_ENTRIES = 500
        private val sourcesKey = stringPreferencesKey("remembered_item_sources")
        private val gson = Gson()

        /** Canonical memory key: "<type>:<id>" with both sides normalized. */
        fun key(type: String, id: String): String? {
            val normalizedId = id.trim().lowercase()
            if (normalizedId.isEmpty()) return null
            return "${canonicalType(type)}:$normalizedId"
        }

        /** Distinct non-blank keys for every id alias of one title. */
        fun keysFor(type: String, vararg ids: String?): List<String> =
            ids.mapNotNull { raw -> raw?.let { key(type, it) } }.distinct()

        /**
         * tv/show/anime/series all mean the addon type "series"; keeps keys
         * interchangeable between route types and Trakt/Calendar raw types.
         */
        private fun canonicalType(type: String): String {
            val normalized = type.trim().lowercase()
            return when (normalized) {
                "tv", "show", "anime", "series" -> "series"
                "" -> "movie"
                else -> normalized
            }
        }

        /** FIFO drop of the eldest entries; exposed for unit tests. */
        internal fun trimToCapacity(
            map: LinkedHashMap<String, String>,
            maxEntries: Int
        ): LinkedHashMap<String, String> {
            while (map.size > maxEntries) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
            return map
        }

        internal fun parseStored(raw: String?): LinkedHashMap<String, String> {
            if (raw.isNullOrBlank()) return LinkedHashMap()
            return try {
                gson.fromJson<LinkedHashMap<String, String>>(
                    raw,
                    object : TypeToken<LinkedHashMap<String, String>>() {}.type
                ) ?: LinkedHashMap()
            } catch (_: Exception) {
                LinkedHashMap()
            }
        }
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    /** First matching alias, or null when the title was never remembered. */
    suspend fun get(keys: List<String>): String? {
        if (keys.isEmpty()) return null
        val stored = parseStored(store().data.first()[sourcesKey])
        val hit = keys.firstNotNullOfOrNull { stored[it] }
        if (hit != null) {
            Log.d(TAG, "hit keys=${keys.size} -> $hit")
        }
        return hit
    }

    /**
     * Remembers [source] under every alias key. Idempotent: re-opening the
     * same title with the same addon does not rewrite the store.
     */
    suspend fun remember(aliasKeys: List<String>, source: String) {
        val normalizedSource = source.trim().trimEnd('/')
        val validKeys = aliasKeys.filter { it.isNotBlank() }.distinct()
        if (normalizedSource.isEmpty() || validKeys.isEmpty()) return
        store().edit { prefs ->
            val current = parseStored(prefs[sourcesKey])
            val alreadyStored = validKeys.all { existing ->
                current[existing]?.equals(normalizedSource, ignoreCase = true) == true
            }
            if (alreadyStored) return@edit
            validKeys.forEach { current.remove(it) }
            validKeys.forEach { current[it] = normalizedSource }
            trimToCapacity(current, MAX_ENTRIES)
            prefs[sourcesKey] = gson.toJson(current)
        }
        Log.d(TAG, "write keys=${validKeys.size} -> $normalizedSource")
    }
}
