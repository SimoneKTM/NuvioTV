package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TvdbSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tvdb_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("tvdb_enabled")
    private val apiKeyKey = stringPreferencesKey("tvdb_api_key")
    private val languageKey = stringPreferencesKey("tvdb_language")
    private val useTrailersKey = booleanPreferencesKey("tvdb_use_trailers")
    private val useArtworkKey = booleanPreferencesKey("tvdb_use_artwork")
    private val useBasicInfoKey = booleanPreferencesKey("tvdb_use_basic_info")
    private val useCreditsKey = booleanPreferencesKey("tvdb_use_credits")
    private val useEpisodesKey = booleanPreferencesKey("tvdb_use_episodes")
    private val useSeasonPostersKey = booleanPreferencesKey("tvdb_use_season_posters")

    val settings: Flow<TvdbSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val storedKey = prefs[apiKeyKey] ?: ""
            val apiKey = storedKey.ifBlank { com.nuvio.tv.BuildConfig.TVDB_API_KEY }
            TvdbSettings(
                enabled = (prefs[enabledKey] ?: false) && apiKey.isNotBlank(),
                apiKey = apiKey,
                language = prefs[languageKey] ?: "en",
                useTrailers = prefs[useTrailersKey] ?: true,
                useArtwork = prefs[useArtworkKey] ?: true,
                useBasicInfo = prefs[useBasicInfoKey] ?: true,
                useCredits = prefs[useCreditsKey] ?: true,
                useEpisodes = prefs[useEpisodesKey] ?: true,
                useSeasonPosters = prefs[useSeasonPostersKey] ?: true
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit {
            it[apiKeyKey] = apiKey.trim()
            if (apiKey.isBlank()) it[enabledKey] = false
        }
    }

    suspend fun setLanguage(language: String) {
        store().edit { it[languageKey] = normalizeTvdbLanguage(language) }
    }

    suspend fun setUseTrailers(enabled: Boolean) {
        store().edit { it[useTrailersKey] = enabled }
    }

    suspend fun setUseArtwork(enabled: Boolean) {
        store().edit { it[useArtworkKey] = enabled }
    }

    suspend fun setUseBasicInfo(enabled: Boolean) {
        store().edit { it[useBasicInfoKey] = enabled }
    }

    suspend fun setUseCredits(enabled: Boolean) {
        store().edit { it[useCreditsKey] = enabled }
    }

    suspend fun setUseEpisodes(enabled: Boolean) {
        store().edit { it[useEpisodesKey] = enabled }
    }

    suspend fun setUseSeasonPosters(enabled: Boolean) {
        store().edit { it[useSeasonPostersKey] = enabled }
    }
}

internal fun normalizeTvdbLanguage(value: String?): String {
    val trimmed = value?.trim()?.replace('_', '-') ?: return "en"
    return trimmed.takeIf { it.isNotBlank() } ?: "en"
}
