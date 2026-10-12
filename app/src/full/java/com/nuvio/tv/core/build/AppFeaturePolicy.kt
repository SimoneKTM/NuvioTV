package com.nuvio.tv.core.build

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = true
    val inAppUpdatesEnabled: Boolean = true
    // Su dispositivi con poca RAM (es. Fire TV Stick ~900MB) il trailer in-app
    // fa arrivare il processo oltre il limite di lmkd: sotto soglia si usa solo
    // l'immagine statica e il trailer, se richiesto, si apre all'esterno.
    val inAppTrailerPlaybackEnabled: Boolean = !DeviceMemoryPolicy.isLowMemoryDevice
    val externalTrailerPlaybackEnabled: Boolean = true
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
}
