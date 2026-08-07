package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeAddonPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "anime_addon_preferences"
    }

    private fun effectiveProfileId(): Int {
        val active = profileManager.activeProfile
        return if (active != null && active.usesPrimaryAddons) 1 else profileManager.activeProfileId.value
    }

    private fun store(profileId: Int = effectiveProfileId()) =
        factory.get(profileId, FEATURE)

    private val effectiveProfileIdFlow: Flow<Int> = combine(
        profileManager.activeProfileId,
        profileManager.profiles
    ) { activeProfileId, profiles ->
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (activeProfile?.usesPrimaryAddons == true) 1 else activeProfileId
    }.distinctUntilChanged()

    private val gson = Gson()
    private val orderedUrlsKey = stringPreferencesKey("anime_addon_urls_ordered")
    private val addonEnabledStatesKey = stringPreferencesKey("anime_addon_enabled_states")
    private val manifestSuffix = "/manifest.json"

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(manifestSuffix, ignoreCase = true)) {
            path.dropLast(manifestSuffix.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    val installedAnimeAddonUrls: Flow<List<String>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            preferences[orderedUrlsKey]?.let(::parseUrlList).orEmpty()
        }
    }

    val animeAddonEnabledStates: Flow<Map<String, Boolean>> = effectiveProfileIdFlow.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            preferences[addonEnabledStatesKey]
                ?.let(::parseEnabledStateMap)
                .orEmpty()
        }
    }

    suspend fun addAnimeAddon(url: String) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            val current = getCurrentList(preferences)
            val normalizedUrl = canonicalizeUrl(url)
            if (current.any { canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true) }) return@edit
            preferences[orderedUrlsKey] = gson.toJson(current + normalizedUrl)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states[normalizedUrl] = true
            preferences[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    suspend fun removeAnimeAddon(url: String) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            val current = getCurrentList(preferences).toMutableList()
            val normalizedUrl = canonicalizeUrl(url)
            val indexToRemove = current.indexOfFirst {
                canonicalizeUrl(it).equals(normalizedUrl, ignoreCase = true)
            }
            if (indexToRemove != -1) {
                current.removeAt(indexToRemove)
            }
            preferences[orderedUrlsKey] = gson.toJson(current)
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states.remove(normalizedUrl)
            preferences[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    suspend fun setAnimeAddonOrder(urls: List<String>) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            val orderedUrls = urls.map(::canonicalizeUrl)
            preferences[orderedUrlsKey] = gson.toJson(orderedUrls)
            val currentStates = getCurrentEnabledStates(preferences)
            preferences[addonEnabledStatesKey] = gson.toJson(
                orderedUrls.associateWith { url -> currentStates[url] ?: true }
            )
        }
    }

    suspend fun setAnimeAddonEnabled(url: String, enabled: Boolean) {
        val active = profileManager.activeProfile
        if (active != null && !active.isPrimary && active.usesPrimaryAddons) return
        store().edit { preferences ->
            val states = getCurrentEnabledStates(preferences).toMutableMap()
            states[canonicalizeUrl(url)] = enabled
            preferences[addonEnabledStatesKey] = gson.toJson(states)
        }
    }

    private fun getCurrentList(preferences: Preferences): List<String> {
        val json = preferences[orderedUrlsKey] ?: return emptyList()
        return parseUrlList(json)
    }

    private fun parseUrlList(json: String): List<String> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getCurrentEnabledStates(preferences: Preferences): Map<String, Boolean> {
        val json = preferences[addonEnabledStatesKey] ?: return emptyMap()
        return parseEnabledStateMap(json)
    }

    private fun parseEnabledStateMap(json: String): Map<String, Boolean> {
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val parsed: Map<String, Boolean> = gson.fromJson(json, type) ?: emptyMap()
            parsed.mapKeys { (url, _) -> canonicalizeUrl(url) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun currentUrls(): List<String> = installedAnimeAddonUrls.first()
}
