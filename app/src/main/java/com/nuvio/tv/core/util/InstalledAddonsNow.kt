package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The installed-addon flows are gated on the manifest cache being read from disk, so a bare
 * `.first()` can suspend for an unbounded time (spinner forever) if that gate never completes.
 * These helpers cap the wait and degrade to an empty list instead of hanging or crashing the
 * caller's scope.
 */
private const val INSTALLED_ADDONS_TIMEOUT_MS = 8_000L

suspend fun AddonRepository.installedAddonsNow(): List<Addon> =
    runCatching {
        withTimeoutOrNull(INSTALLED_ADDONS_TIMEOUT_MS) { getInstalledAddons().first() }
    }.getOrNull() ?: emptyList()

suspend fun AddonRepository.enabledAddonsNow(): List<Addon> = installedAddonsNow().enabledAddons()

suspend fun AnimeAddonRepository.installedAnimeAddonsNow(): List<Addon> =
    runCatching {
        withTimeoutOrNull(INSTALLED_ADDONS_TIMEOUT_MS) { getInstalledAnimeAddons().first() }
    }.getOrNull() ?: emptyList()

suspend fun AnimeAddonRepository.enabledAnimeAddonsNow(): List<Addon> =
    installedAnimeAddonsNow().enabledAddons()

suspend fun ExtraAddonRepository.installedExtraAddonsNow(): List<Addon> =
    runCatching {
        withTimeoutOrNull(INSTALLED_ADDONS_TIMEOUT_MS) { getInstalledExtraAddons().first() }
    }.getOrNull() ?: emptyList()

suspend fun ExtraAddonRepository.enabledExtraAddonsNow(): List<Addon> =
    installedExtraAddonsNow().enabledAddons()
