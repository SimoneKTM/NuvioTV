package com.nuvio.tv.core.logo

/**
 * Override manuali del logo per titolo ("Imposta logo personalizzato" nel
 * menu Azioni Titolo): vince sul logo TMDB dell'hero, delle card, del
 * dettaglio e del player. Vedi [UrlOverrideStore] per il funzionamento.
 */
object LogoOverrideStore : UrlOverrideStore("nuvio_logo_overrides")
