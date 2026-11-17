package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrailerSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trailer_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("trailer_enabled")
    private val delaySecondsKey = intPreferencesKey("trailer_delay_seconds")
    // Quality ceiling for the shared preview player: 720p (default, safe on
    // low-RAM sticks) or 1080p (opt-in, memory-gated in TrailerPlayerPool).
    private val maxVideoHeightKey = intPreferencesKey("trailer_max_video_height")

    val settings: Flow<TrailerSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            TrailerSettings(
                enabled = prefs[enabledKey] ?: true,
                delaySeconds = prefs[delaySecondsKey] ?: 7,
                maxVideoHeight = prefs[maxVideoHeightKey]?.takeIf { it == 720 || it == 1080 } ?: 720
            )
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setDelaySeconds(seconds: Int) {
        store().edit { it[delaySecondsKey] = seconds }
    }

    suspend fun setMaxVideoHeight(height: Int) {
        store().edit { it[maxVideoHeightKey] = if (height >= 1080) 1080 else 720 }
    }
}

data class TrailerSettings(
    val enabled: Boolean = true,
    val delaySeconds: Int = 7,
    val maxVideoHeight: Int = 720
)
