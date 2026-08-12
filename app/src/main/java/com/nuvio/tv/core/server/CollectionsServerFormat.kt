package com.nuvio.tv.core.server

import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.CollectionSource
import com.nuvio.tv.domain.model.LiveTvCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource

internal fun collectionsToServerFormat(cols: List<Collection>): List<CollectionInfo> {
    return cols.map { col ->
        CollectionInfo(
            id = col.id,
            title = col.title,
            backdropImageUrl = col.backdropImageUrl,
            pinToTop = col.pinToTop,
            focusGlowEnabled = col.focusGlowEnabled,
            viewMode = col.viewMode.name,
            showAllTab = col.showAllTab,
            folders = col.folders.map { folder ->
                FolderInfo(
                    id = folder.id,
                    title = folder.title,
                    coverImageUrl = folder.coverImageUrl,
                    focusGifUrl = folder.focusGifUrl,
                    focusGifEnabled = folder.focusGifEnabled,
                    coverEmoji = folder.coverEmoji,
                    tileShape = folder.tileShape.name,
                    hideTitle = folder.hideTitle,
                    heroBackdropUrl = folder.heroBackdropUrl,
                    heroVideoUrl = folder.heroVideoUrl,
                    titleLogoUrl = folder.titleLogoUrl,
                    catalogSources = folder.catalogSources.map { src ->
                        CatalogSourceInfo(
                            addonId = src.addonId,
                            type = src.type,
                            catalogId = src.catalogId,
                            genre = src.genre,
                            animeAddon = src.animeAddon
                        )
                    },
                    sources = folder.sources.map { source ->
                        when (source) {
                            is AddonCatalogCollectionSource -> CollectionSourceInfo(
                                provider = "addon",
                                addonId = source.addonId,
                                type = source.type,
                                catalogId = source.catalogId,
                                genre = source.genre,
                                animeAddon = source.animeAddon
                            )
                            is TmdbCollectionSource -> CollectionSourceInfo(
                                provider = "tmdb",
                                tmdbSourceType = source.sourceType.name,
                                title = source.title,
                                tmdbId = source.tmdbId,
                                mediaType = source.mediaType.name,
                                sortBy = source.sortBy,
                                filters = TmdbFiltersInfo(
                                    withGenres = source.filters.withGenres,
                                    releaseDateGte = source.filters.releaseDateGte,
                                    releaseDateLte = source.filters.releaseDateLte,
                                    voteAverageGte = source.filters.voteAverageGte,
                                    voteAverageLte = source.filters.voteAverageLte,
                                    voteCountGte = source.filters.voteCountGte,
                                    withOriginalLanguage = source.filters.withOriginalLanguage,
                                    withOriginCountry = source.filters.withOriginCountry,
                                    withKeywords = source.filters.withKeywords,
                                    withCompanies = source.filters.withCompanies,
                                    withNetworks = source.filters.withNetworks,
                                    year = source.filters.year,
                                    watchRegion = source.filters.watchRegion,
                                    withWatchProviders = source.filters.withWatchProviders
                                )
                            )
                            is TraktCollectionSource -> CollectionSourceInfo(
                                provider = "trakt",
                                title = source.title,
                                traktListId = source.traktListId,
                                mediaType = source.mediaType.name,
                                sortBy = source.sortBy,
                                sortHow = source.sortHow
                            )
                            is LiveTvCollectionSource -> CollectionSourceInfo(
                                provider = "livetv",
                                catalogId = source.playlistId,
                                title = source.playlistName
                            )
                        }
                    }
                )
            }
        )
    }
}
