package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.newEpisodeNoticeDataStore: DataStore<Preferences> by preferencesDataStore(name = "new_episode_notice")

/**
 * La notifica "Nuovo episodio disponibile" va mostrata una sola volta nella
 * vita dell'app: dopo la prima visualizzazione (manuale o automatica) non
 * ricompare più, neanche all'avvio successivo.
 */
@Singleton
class NewEpisodeNoticeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.newEpisodeNoticeDataStore
    private val hasShownKey = booleanPreferencesKey("new_episode_notice_has_shown")

    val hasShown: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[hasShownKey] ?: false
    }

    suspend fun markShown() {
        dataStore.edit { prefs ->
            prefs[hasShownKey] = true
        }
    }
}
