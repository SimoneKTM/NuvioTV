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

private val Context.startupWarmupDataStore: DataStore<Preferences> by preferencesDataStore(name = "startup_warmup")

/**
 * Segna che almeno un warm-up Home è andato a buon fine: il primo avvio tiene
 * la schermata nera finché tutto non è pronto (righe, immagini, trailer), gli
 * avvii successivi partono istantaneamente dalla cache su disco.
 */
@Singleton
class StartupWarmupPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.startupWarmupDataStore
    private val firstHomeReadyKey = booleanPreferencesKey("first_home_ready_done")

    val firstHomeReadyDone: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[firstHomeReadyKey] ?: false
    }

    suspend fun markFirstHomeReady() {
        dataStore.edit { prefs ->
            prefs[firstHomeReadyKey] = true
        }
    }
}
