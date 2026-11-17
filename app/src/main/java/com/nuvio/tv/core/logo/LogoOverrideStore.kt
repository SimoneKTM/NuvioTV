package com.nuvio.tv.core.logo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Override manuali del logo per titolo ("Imposta logo personalizzato" nel
 * menu Azioni Titolo).
 *
 * Chiave = id item (lo stesso usato da hero, card, dettaglio e player),
 * valore = URL dell'immagine. SharedPreferences + mappa in memoria: le
 * letture in composizione sono sincrone e l'[overrides] StateFlow fa
 * ricomporre i punti di display appena l'utente salva.
 */
object LogoOverrideStore {
    private const val PREFS_NAME = "nuvio_logo_overrides"

    private lateinit var prefs: SharedPreferences
    private val _overrides = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Mappa id -> URL logo personalizzato (vuota se nessun override). */
    val overrides: StateFlow<Map<String, String>> = _overrides.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _overrides.value = prefs.all.mapNotNull { (key, value) ->
                (value as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key to it }
            }.toMap()
            initialized = true
        }
    }

    /**
     * Logo da mostrare: l'override manuale se presente per [itemId],
     * altrimenti il logo originale (che può essere null → fallback testo).
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
