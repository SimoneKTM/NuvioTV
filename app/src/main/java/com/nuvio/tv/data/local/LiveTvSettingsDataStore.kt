package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.LiveTvPlaylist
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Singleton
class LiveTvSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "live_tv_settings"
        private const val LISTS_KEY = "live_tv_playlists"
        private val gson = Gson()
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val playlistsKey = stringPreferencesKey(LISTS_KEY)

    private val type = object : TypeToken<List<LiveTvPlaylist>>() {}.type

    val playlists: Flow<List<LiveTvPlaylist>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val json = prefs[playlistsKey] ?: return@map emptyList()
            runCatching { gson.fromJson<List<LiveTvPlaylist>>(json, type) }
                .getOrNull()
                ?.filter { it.id.isNotBlank() && it.sourceUrl.isNotBlank() }
                ?: emptyList()
        }
    }

    suspend fun setPlaylists(lists: List<LiveTvPlaylist>) {
        store().edit { it[playlistsKey] = gson.toJson(lists.distinctBy { p -> p.id }) }
    }
}