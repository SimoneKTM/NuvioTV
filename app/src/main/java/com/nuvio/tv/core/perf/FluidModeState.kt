package com.nuvio.tv.core.perf

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Single source of truth for Layout > Fluid Mode.
 *
 * Every "make it smoother on a Fire TV stick" behaviour introduced while tuning
 * performance is gated on this flag: with Fluid Mode off the app keeps the
 * structural behaviour of the build before the speed-ups (startup splash timing,
 * preload structure, detail paint order, duplicate-request deduplication, ...).
 * The default is off, so non-stick devices keep the classic behaviour.
 */
@Singleton
class FluidModeState @Inject constructor(
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    @param:Named("anime_layout") private val animeLayoutPreferenceDataStore: LayoutPreferenceDataStore,
    @param:Named("extra_layout") private val extraLayoutPreferenceDataStore: LayoutPreferenceDataStore
) {
    /** OR of every layout store — same rule as the zoom mirror in MainActivity. */
    val enabled: Flow<Boolean> = combine(
        layoutPreferenceDataStore.fluidModeEnabled,
        animeLayoutPreferenceDataStore.fluidModeEnabled,
        extraLayoutPreferenceDataStore.fluidModeEnabled
    ) { base, anime, extra -> base || anime || extra }

    suspend fun isEnabled(): Boolean = runCatching { enabled.first() }.getOrElse { false }
}
