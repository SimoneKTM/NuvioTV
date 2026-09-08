package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class CustomTabSource(
    val addonId: String,
    val type: String,
    val catalogId: String,
    val genre: String? = null
)

@Immutable
data class CustomTab(
    val id: String,
    val name: String,
    val icon: String = "DEFAULT",
    val sources: List<CustomTabSource> = emptyList(),
    val order: Int = 0,
    val enabled: Boolean = true
) {
    companion object {
        private const val MAX_TABS = 3
        fun getMaxTabs(): Int = MAX_TABS
    }
}

enum class CustomTabIcon(
    val displayName: String,
    val materialIcon: String
) {
    DEFAULT("Predefinita", "Default"),
    MOVIE("Film", "Movie"),
    SERIES("Serie TV", "LiveTv"),
    ANIME("Anime", "FilterDrama"),
    DOCUMENTARY("Documentari", "VideoLibrary"),
    KIDS("Bambini", "ChildFriendly"),
    SPORTS("Sport", "SportsEsports"),
    MUSIC("Musica", "MusicNote"),
    NEWS("Notizie", "Newspaper"),
    SCIENCE_FI("Fantascienza", "RocketLaunch"),
    HORROR("Horror", "Skull"),
    COMEDY("Commedia", "SentimentSatisfied"),
    ACTION("Azione", "FlashOn"),
    DRAMA("Drammatico", "TheaterComedy"),
    CUSTOM("Personalizzata", "Edit");
}