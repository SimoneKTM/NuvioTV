package com.nuvio.tv.ui.screens.settings

import android.content.Context
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    metaRepository: com.nuvio.tv.domain.repository.MetaRepository
) : LayoutSettingsViewModel(
    context = context,
    layoutPreferenceDataStore = layoutPreferenceDataStore,
    streamBadgeSettingsDataStore = streamBadgeSettingsDataStore,
    traktSettingsDataStore = traktSettingsDataStore,
    trailerSettingsDataStore = trailerSettingsDataStore,
    addonRepository = addonRepository,
    metaRepository = metaRepository
)
