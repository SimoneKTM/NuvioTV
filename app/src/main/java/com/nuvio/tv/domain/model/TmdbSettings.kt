package com.nuvio.tv.domain.model

data class TmdbSettings(
    val enabled: Boolean = false,
    val modernHomeEnabled: Boolean = false,
    val enrichContinueWatching: Boolean = true,
    // TMDB language preference (ISO-639-1); follows the system language unless overridden
    val language: String = systemMetadataLanguage(),
    // Raw stored preference: "system" or an explicit language code (used by the settings UI)
    val languagePreference: String = METADATA_LANGUAGE_SYSTEM,
    // Group: Artwork (logo, backdrop)
    val useArtwork: Boolean = true,
    // Group: Basic Info (description, genres, rating)
    val useBasicInfo: Boolean = true,
    // Group: Details (runtime, status, country, language)
    val useDetails: Boolean = true,
    // Group: Release Dates (release date / air date)
    val useReleaseDates: Boolean = false,
    // Group: Credits (cast with photos, director, writer)
    val useCredits: Boolean = true,
    // Group: Production companies
    val useProductions: Boolean = true,
    // Group: Networks (logo)
    val useNetworks: Boolean = true,
    // Group: Episodes (episode titles, overviews, thumbnails)
    val useEpisodes: Boolean = true,
    // Group: Trailers (TMDB trailer candidates merged into detail metadata)
    val useTrailers: Boolean = true,
    // Group: Recommendations (more like this)
    val useMoreLikeThis: Boolean = true,
    // Group: Collections
    val useCollections: Boolean = true
)
