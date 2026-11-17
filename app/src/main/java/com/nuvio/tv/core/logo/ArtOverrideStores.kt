package com.nuvio.tv.core.logo

/**
 * Override manuale del POSTER per titolo ("Imposta poster personalizzato"
 * nel menu Azioni Titolo). Vedi [UrlOverrideStore].
 */
object PosterOverrideStore : UrlOverrideStore("nuvio_poster_overrides")

/**
 * Override manuale dello SFONDO della hero per titolo ("Imposta sfondo hero
 * personalizzato" nel menu Azioni Titolo). Vedi [UrlOverrideStore].
 */
object HeroBackdropOverrideStore : UrlOverrideStore("nuvio_hero_backdrop_overrides")
