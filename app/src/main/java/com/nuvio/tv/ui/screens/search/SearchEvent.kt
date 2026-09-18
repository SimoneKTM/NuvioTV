package com.nuvio.tv.ui.screens.search

sealed interface SearchEvent {
    data class QueryChanged(val query: String) : SearchEvent
    data object SubmitSearch : SearchEvent
    data object ClearRecentSearches : SearchEvent

    data class LoadMoreCatalog(
        val catalogId: String,
        val addonId: String,
        val type: String
    ) : SearchEvent

    data object Retry : SearchEvent

    data class DiscoverTypeChanged(val type: String) : SearchEvent
    data class DiscoverCatalogChanged(val catalogKey: String) : SearchEvent
    data class DiscoverGenreChanged(val genre: String?) : SearchEvent
    data object LoadMoreDiscoverResults : SearchEvent
}
