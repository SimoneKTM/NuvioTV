package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AnimeTmdbSettingsViewModel @Inject constructor(
    @Named("anime_tmdb") dataStore: TmdbSettingsDataStore,
    trailerService: TrailerService,
    metaRepository: MetaRepository,
    cwEnrichmentCache: ContinueWatchingEnrichmentCache
) : TmdbSettingsViewModel(
    dataStore = dataStore,
    trailerService = trailerService,
    metaRepository = metaRepository,
    cwEnrichmentCache = cwEnrichmentCache
)
