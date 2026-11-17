package com.nuvio.tv.core.logo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Base per gli override manuali di immagini per titolo (logo, poster,
 * sfondo hero): SharedPreferences + mappa in memoria, così le letture in
 * composizione sono sincrone e il [overrides] StateFlow fa ricomporre i
 * punti di display appena l'utente salva.
 *
 * Chiave = id item (lo stesso usato da hero, card, dettaglio e player).
 * Estende questa classe: [LogoOverrideStore], [PosterOverrideStore],
 * [HeroBackdropOverrideStore].
 */
open class UrlOverrideStore(private val prefsName: String) {

    private lateinit var prefs: SharedPreferences
    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Mappa id -> URL immagine personalizzata (vuota se nessun override). */
    val overrides: StateFlow<Map<String, String>> = _overrides.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext
                .getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            _overrides.value = prefs.all.mapNotNull { (key, value) ->
                (value as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key to it }
            }.toMap()
            initialized = true
        }
    }

    /**
     * Immagine da mostrare: l'override manuale se presente per [itemId],
     * altrimenti l'originale.
     */
    fun resolve(itemId: String?, original: String?): String? {
        if (itemId == null || !initialized) return original
        return _overrides.value[itemId] ?: original
    }

    /** Override corrente per [itemId], o null. */
    fun current(itemId: String?): String? =
        itemId?.takeIf { initialized }?.let { _overrides.value[it] }

    /**
     * Impie (o rimuove con null/blank) l'override per [itemId].
     * Applica subito alla mappa in memoria: l'UI si aggiorna in tempo reale.
     */
    fun set(itemId: String, url: String?) {
        if (!initialized) return
        val normalized = url?.trim()?.takeIf { it.isNotBlank() }
        val editor = prefs.edit()
        if (normalized == null) {
            editor.remove(itemId)
        } else {
            editor.putString(itemId, normalized)
        }
        editor.apply()
        _overrides.value = if (normalized == null) {
            _overrides.value - itemId
        } else {
            _overrides.value + (itemId to normalized)
        }
    }
}
