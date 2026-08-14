package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AnimeLayoutSettingsViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    @Named("anime_layout") layoutPreferenceDataStore: LayoutPreferenceDataStore,
    streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    traktSettingsDataStore: TraktSettingsDataStore,
    trailerSettingsDataStore: TrailerSettingsDataStore,
    addonRepository: AddonRepository,
    metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val animeAddonRepository: AnimeAddonRepository
) : LayoutSettingsViewModel(
    context = context,
    layoutPreferenceDataStore = layoutPreferenceDataStore,
    streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
    traktSettingsDataStore = traktSettingsDataStore,
    trailerSettingsDataStore = trailerSettingsDataStore,
    addonRepository = addonRepository,
    metaRepository = metaRepository
) {
    override val homeOnlyLayout: Boolean = true

    override fun loadAvailableCatalogs() {
        viewModelScope.launch {
            try {
                animeAddonRepository.getInstalledAnimeAddons()
                    .distinctUntilChanged()
                    .collectLatest { installedAddons ->
                        val addons = installedAddons.enabledAddons()
                        val catalogs = addons.flatMap { addon ->
                            addon.catalogs
                                .filter { catalog ->
                                    !catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired }
                                }
                                .map { catalog ->
                                    CatalogInfo(
                                        key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                                        name = catalog.name,
                                        addonName = addon.displayName
                                    )
                                }
                        }.distinctBy { it.key }
                        updateUiStateIfChanged { it.copy(availableCatalogs = catalogs) }
                    }
            } catch (e: Exception) {
                updateUiStateIfChanged { it.copy(availableCatalogs = emptyList()) }
            }
        }
    }
}
