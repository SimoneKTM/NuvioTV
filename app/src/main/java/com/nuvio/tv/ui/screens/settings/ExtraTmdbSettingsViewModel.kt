package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerService
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class ExtraTmdbSettingsViewModel @Inject constructor(
    @Named("extra_tmdb") dataStore: TmdbSettingsDataStore,
    trailerService: TrailerService,
    metaRepository: MetaRepository,
    @Named("extra_cw_cache") cwEnrichmentCache: ContinueWatchingEnrichmentCache
) : TmdbSettingsViewModel(
    dataStore = dataStore,
    trailerService = trailerService,
    metaRepository = metaRepository,
    cwEnrichmentCache = cwEnrichmentCache
)
