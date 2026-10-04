package com.nuvio.tv.core.sync

import android.content.Context
import android.util.Log
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Precarica su disco le immagini delle prime pagine Home (poster, sfondi, loghi)
 * appena i cataloghi sono caldati: quando la Home parte le card trovano già i file
 * nella cache disco di Coil e lo scroll non scarica più niente.
 *
 * La richiesta parte a 300x450 px (decodifica piccola): la cache disco di Coil è
 * indicizzata dall'URL, quindi la successiva richiesta della card (qualsiasi taglia)
 * trova il file già scaricato.
 */
@Singleton
class HomeImagePreloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepository: CatalogRepository
) {
    companion object {
        private const val TAG = "HomeImagePreloader"
        private const val ITEMS_PER_ROW = 12
        private const val MAX_IMAGES = 180
        private const val CONCURRENCY = 4
        private const val PREFETCH_WIDTH_PX = 300
        private const val PREFETCH_HEIGHT_PX = 450
        private const val WARM_MEMORY_KEY_PREFIX = "warm_"
    }

    /**
     * Scarica tutte le immagini delle prime pagine. Ritorna false solo se le righe
     * non sono ancora pronte (la fase viene riprovata); se il warm-up cataloghi è
     * finito senza righe non c'è nulla da fare e ritorna true.
     */
    suspend fun warmImages(): Boolean = withContext(Dispatchers.IO) {
        val rows = catalogRepository.firstPageRows()
        if (rows.isEmpty()) {
            return@withContext catalogRepository.warmComplete.value
        }

        val urls = LinkedHashSet<String>()
        rowLoop@ for (row in rows) {
            for (item in row.items.take(ITEMS_PER_ROW)) {
                item.poster?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                item.background?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                item.landscapePoster?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                item.logo?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                if (urls.size >= MAX_IMAGES) break@rowLoop
            }
        }
        if (urls.isEmpty()) return@withContext true

        val targets = urls.toList()
        val imageLoader = context.imageLoader
        val semaphore = Semaphore(CONCURRENCY)
        coroutineScope {
            targets.map { url ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            val key = WARM_MEMORY_KEY_PREFIX + url
                            imageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .memoryCacheKey(key)
                                    .size(PREFETCH_WIDTH_PX, PREFETCH_HEIGHT_PX)
                                    .crossfade(false)
                                    .build()
                            )
                            // Libera subito la memoria: il file resta nella cache disco.
                            imageLoader.memoryCache?.remove(MemoryCache.Key(key))
                        }.onFailure {
                            Log.d(TAG, "Warm image failed url=$url ${it.message}")
                        }
                    }
                }
            }.awaitAll()
        }
        Log.d(TAG, "Warmed ${targets.size} images from ${rows.size} rows")
        true
    }
}
