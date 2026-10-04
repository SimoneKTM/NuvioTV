package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prima pagina di un catalogo salvata su disco: serve a far partire la Home
 * dall'ultimo stato noto (stile Netflix) invece di aspettare la rete ad ogni
 * avvio. [body] è la risposta JSON cruda del catalogo.
 */
@JsonClass(generateAdapter = true)
data class CatalogSnapshotEntry(
    val savedAtMs: Long,
    val body: String,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val rawType: String,
    val supportsSkip: Boolean,
    val skipStep: Int
)

/**
 * Snapshot su disco delle prime pagine dei cataloghi Home.
 *
 * Scritto quando una prima pagina viene caricata (warm-up o navigazione),
 * riletto all'avvio successivo: così il gate di partenza si sblocca senza
 * rete e, se la rete fallisce, la Home mostra comunque le ultime righe note.
 */
@Singleton
class CatalogSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "CatalogSnapshot"
        private const val FILE_NAME = "catalog_snapshot.json"
        private const val TMP_NAME = "catalog_snapshot.json.tmp"
    }

    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        CatalogSnapshotEntry::class.java
    )
    private val adapter: com.squareup.moshi.JsonAdapter<Map<String, CatalogSnapshotEntry>> =
        moshi.adapter(mapType)
    private var cached: MutableMap<String, CatalogSnapshotEntry>? = null

    suspend fun readAll(): Map<String, CatalogSnapshotEntry> = mutex.withLock { loadLocked() }

    suspend fun put(url: String, entry: CatalogSnapshotEntry) = mutex.withLock {
        val map = loadLocked().toMutableMap()
        map[url] = entry
        cached = map
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.filesDir, TMP_NAME)
                tmp.writeText(adapter.toJson(map))
                if (!tmp.renameTo(file)) {
                    file.writeText(adapter.toJson(map))
                    tmp.delete()
                }
            }.onFailure { Log.w(TAG, "Snapshot save failed: ${it.message}") }
        }
    }

    private fun loadLocked(): Map<String, CatalogSnapshotEntry> {
        cached?.let { return it }
        val loaded = runCatching {
            if (file.exists()) adapter.fromJson(file.readText()) else null
        }.getOrElse {
            Log.w(TAG, "Snapshot load failed: ${it.message}")
            null
        }
        val map = loaded ?: emptyMap()
        cached = map.toMutableMap()
        return map
    }
}
