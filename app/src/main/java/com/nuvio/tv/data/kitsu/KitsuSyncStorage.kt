package com.nuvio.tv.data.kitsu

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface KitsuSyncStorage {
    suspend fun load(profileId: Int): String?
    suspend fun save(profileId: Int, payload: String)
    suspend fun remove(profileId: Int)
}

@Singleton
class AndroidKitsuSyncStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : KitsuSyncStorage {
    override suspend fun load(profileId: Int): String? =
        factory.get(profileId, FEATURE_NAME).data.first()[SNAPSHOT_KEY]

    override suspend fun save(profileId: Int, payload: String) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences[SNAPSHOT_KEY] = payload
        }
    }

    override suspend fun remove(profileId: Int) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences.remove(SNAPSHOT_KEY)
        }
    }

    private companion object {
        const val FEATURE_NAME = "kitsu_sync"
        val SNAPSHOT_KEY = stringPreferencesKey("snapshot")
    }
}