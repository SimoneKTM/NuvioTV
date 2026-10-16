package com.nuvio.tv.data.local

import android.util.Log
import com.google.gson.Gson
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.MetaPreview
import java.io.File
import java.time.LocalDate

/**
 * Snapshot persistente della riga "Ultime Uscite" (calendario mensile Trakt
 * già arricchito e shaping 25+30). Senza di essa la riga non può nascere
 * prima del round-trip Trakt del mese (~15-25s su una Fire TV Stick): con
 * lo snapshot la prima emissione del flusso dipinge la riga in pochi
 * secondi e il fetch di rete la aggiorna in background. Viene scartato
 * automaticamente se la chiave mese non è più quella corrente.
 */
internal class LatestReleaseMonthStore(
    private val file: File
) {
    internal data class Snapshot(
        val monthKey: String? = null,
        val savedAtMs: Long = 0L,
        val items: List<Entry> = emptyList()
    )

    internal data class Entry(
        val meta: MetaPreview? = null,
        val releaseDateIso: String? = null,
        val addonName: String = "Trakt",
        val notInCatalog: Boolean = false,
        val episodeLabel: String? = null
    )

    companion object {
        private const val TAG = "LatestReleaseMonthStore"
    }

    private val gson = Gson()

    @Volatile
    private var cached: Snapshot? = null

    fun load(monthKey: String): List<CalendarItem>? {
        cached?.let { snapshot ->
            if (snapshot.monthKey == monthKey) return snapshot.toItems()
        }
        val snapshot = try {
            if (!file.exists()) return null
            gson.fromJson(file.readText(), Snapshot::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read month snapshot: ${e.message}")
            null
        } ?: return null
        cached = snapshot
        return if (snapshot.monthKey == monthKey) snapshot.toItems() else null
    }

    fun save(monthKey: String, items: List<CalendarItem>) {
        try {
            val snapshot = Snapshot(
                monthKey = monthKey,
                savedAtMs = System.currentTimeMillis(),
                items = items.map { item ->
                    Entry(
                        meta = item.meta,
                        releaseDateIso = item.releaseDate?.toString(),
                        addonName = item.addonName,
                        notInCatalog = item.notInCatalog,
                        episodeLabel = item.episodeLabel
                    )
                }
            )
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(snapshot))
            cached = snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write month snapshot: ${e.message}")
        }
    }

    private fun Snapshot.toItems(): List<CalendarItem> = items.mapNotNull { entry ->
        val meta = entry.meta ?: return@mapNotNull null
        CalendarItem(
            meta = meta,
            releaseDate = entry.releaseDateIso?.let { iso ->
                runCatching { LocalDate.parse(iso) }.getOrNull()
            },
            addonName = entry.addonName,
            notInCatalog = entry.notInCatalog,
            episodeLabel = entry.episodeLabel
        )
    }
}
