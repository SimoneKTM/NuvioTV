package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.openSubtitlesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "opensubtitles_settings"
)

@Singleton
class OpenSubtitlesDirectDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val dataStore = context.openSubtitlesDataStore

    private val enabledKey = booleanPreferencesKey("enabled")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val usernameKey = stringPreferencesKey("username")
    private val passwordKey = stringPreferencesKey("password")
    private val userTokenKey = stringPreferencesKey("user_token")
    private val languagesKey = stringSetPreferencesKey("languages")

    data class OpenSubtitlesDirectSettings(
        val enabled: Boolean = false,
        val apiKey: String = "",
        val username: String = "",
        val password: String = "",
        val userToken: String = "",
        val languages: Set<String> = emptySet()
    ) {
        val hasApiKey: Boolean get() = apiKey.isNotBlank()
        val hasUserCredentials: Boolean get() = username.isNotBlank() && password.isNotBlank()
    }

    val settings: Flow<OpenSubtitlesDirectSettings> = dataStore.data.map { prefs ->
        val apiKey = prefs[apiKeyKey].orEmpty().trim()
        OpenSubtitlesDirectSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = apiKey,
            username = prefs[usernameKey].orEmpty().trim(),
            password = prefs[passwordKey].orEmpty().trim(),
            userToken = prefs[userTokenKey].orEmpty().trim(),
            languages = prefs[languagesKey] ?: emptySet()
        ).let { s ->
            if (apiKey.isBlank()) s.copy(enabled = false) else s
        }
    }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { prefs ->
            val apiKey = prefs[apiKeyKey].orEmpty().trim()
            prefs[enabledKey] = value && apiKey.isNotBlank()
        }
    }

    suspend fun setApiKey(value: String) {
        dataStore.edit { prefs ->
            val normalized = value.trim()
            prefs[apiKeyKey] = normalized
            if (normalized.isBlank()) prefs[enabledKey] = false
        }
    }

    suspend fun setUsername(value: String) {
        dataStore.edit { prefs -> prefs[usernameKey] = value.trim() }
    }

    suspend fun setPassword(value: String) {
        dataStore.edit { prefs -> prefs[passwordKey] = value.trim() }
    }

    suspend fun setUserToken(value: String) {
        dataStore.edit { prefs -> prefs[userTokenKey] = value.trim() }
    }

    suspend fun setLanguages(value: Set<String>) {
        dataStore.edit { prefs -> prefs[languagesKey] = value }
    }
}