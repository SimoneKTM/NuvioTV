package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepository"
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val addons = addonRepository.getInstalledAddons().first()
        val enabledAddons = addons.filter { it.enabled }

        if (enabledAddons.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val allItems = mutableListOf<CalendarItem>()
        val catalogsToLoad = enabledAddons.flatMap { addon ->
            addon.catalogs.filter { catalog ->
                val isSearchOnly = catalog.extra.any {
                    it.name.equals("search", ignoreCase = true) && it.isRequired
                }
                !isSearchOnly && (!catalog.hasExplicitShowInHome || catalog.showInHome)
            }.map { addon to it }
        }

        for ((addon, catalog) in catalogsToLoad) {
            try {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.displayName,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    skip = 0,
                    skipStep = catalog.pageSize ?: 50,
                    supportsSkip = false
                ).first { result ->
                    if (result is NetworkResult.Success) {
                        result.data.items.forEach { meta ->
                            val releaseDate = parseEpisodeReleaseLocalDate(meta.released)
                            if (releaseDate != null) {
                                allItems.add(
                                    CalendarItem(
                                        meta = meta,
                                        releaseDate = releaseDate,
                                        addonName = addon.displayName
                                    )
                                )
                            }
                        }
                    }
                    true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load calendar catalog from ${addon.displayName}: ${catalog.id}", e)
            }
        }

        emit(allItems)
    }
}
