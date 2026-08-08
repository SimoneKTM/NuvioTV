package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "vpn_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val configNameKey = stringPreferencesKey("config_name")
    private val configTextKey = stringPreferencesKey("config_text")

    val configName: Flow<String?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[configNameKey] }
    }

    val configText: Flow<String?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> prefs[configTextKey] }
    }

    suspend fun saveConfig(name: String, text: String) {
        store().edit { prefs ->
            prefs[configNameKey] = name
            prefs[configTextKey] = text
        }
    }

    suspend fun removeConfig() {
        store().edit { prefs ->
            prefs.remove(configNameKey)
            prefs.remove(configTextKey)
        }
    }
}