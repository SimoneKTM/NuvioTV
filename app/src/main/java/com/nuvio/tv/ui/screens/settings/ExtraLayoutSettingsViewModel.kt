package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class ExtraLayoutSettingsViewModel @Inject constructor(
    @param:ApplicationContext context: Context,
    @Named("extra_layout") layoutPreferenceDataStore: LayoutPreferenceDataStore,
    streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    trailerSettingsDataStore: TrailerSettingsDataStore,
    addonRepository: AddonRepository,
    metaRepository: com.nuvio.tv.domain.repository.MetaRepository,
    private val extraAddonRepository: ExtraAddonRepository
) : LayoutSettingsViewModel(
    context = context,
    layoutPreferenceDataStore = layoutPreferenceDataStore,
    streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
    trailerSettingsDataStore = trailerSettingsDataStore,
    addonRepository = addonRepository,
    metaRepository = metaRepository
) {
    override val homeOnlyLayout: Boolean = true

    // The Extra tab has no trailer pipeline and its modern home ignores the
    // landscape/fullscreen-hero prefs, so those rows are hidden for this tab.
    override val isExtraLayout: Boolean = true

    override fun loadAvailableCatalogs() {
        viewModelScope.launch {
            try {
                extraAddonRepository.getInstalledExtraAddons()
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
