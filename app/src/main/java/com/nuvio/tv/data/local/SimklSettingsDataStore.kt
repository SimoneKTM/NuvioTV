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
class SimklSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val customClientIdKey = stringPreferencesKey("simkl_custom_client_id")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, "simkl_settings")

    val customClientId: Flow<String> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, "simkl_settings").data.map { prefs ->
            prefs[customClientIdKey] ?: ""
        }
    }

    suspend fun setCustomClientId(clientId: String) {
        store().edit { prefs ->
            if (clientId.isBlank()) {
                prefs.remove(customClientIdKey)
            } else {
                prefs[customClientIdKey] = clientId.trim()
            }
        }
    }
}
