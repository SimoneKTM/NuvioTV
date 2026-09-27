package com.nuvio.tv.data.translation

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.nuvio.tv.domain.model.Meta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class MetadataTextTranslator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MetaTextTranslator"
        private const val CACHE_FILE = "metadata_translation_cache.json"
        private const val MAX_CACHE_ENTRIES = 1_000
        private const val DETECT_TIMEOUT_MS = 3_000L
        private const val MODEL_TIMEOUT_MS = 10_000L
        private const val TRANSLATE_TIMEOUT_MS = 8_000L
        private const val BATCH_DEADLINE_MS = 15_000L
        private const val PARALLELISM = 4
    }

    private val gson = Gson()
    private val mutex = Mutex()
    private val languageIdentifier = LanguageIdentification.getClient()
    @Volatile private var cache: MutableMap<String, String>? = null
    @Volatile private var dirty = false

    suspend fun translateMeta(meta: Meta, targetLanguage: String): Meta = withContext(Dispatchers.IO) {
        if (normalizeLanguageCode(targetLanguage) !in TRANSLATION_SUPPORTED_LANGUAGES) {
            return@withContext meta
        }
        val startedAtMs = System.currentTimeMillis()
        try {
            val newDescription = meta.description?.let { translateText(it, targetLanguage, startedAtMs) }
            val newVideos = translateVideos(meta, targetLanguage, startedAtMs)
            if (newDescription == null && newVideos == meta.videos) {
                meta
            } else {
                meta.copy(description = newDescription ?: meta.description, videos = newVideos)
            }
        } finally {
            withContext(NonCancellable) { persistCacheIfDirty() }
        }
    }

    private suspend fun translateVideos(
        meta: Meta,
        targetLanguage: String,
        startedAtMs: Long
    ): List<com.nuvio.tv.domain.model.Video> {
        if (meta.videos.none { !it.overview.isNullOrBlank() }) return meta.videos
        return coroutineScope {
            val semaphore = Semaphore(PARALLELISM)
            meta.videos
                .map { video ->
                    async {
                        val overview = video.overview
                        if (overview.isNullOrBlank()) return@async video
                        semaphore.withPermit {
                            val translated = translateText(overview, targetLanguage, startedAtMs)
                            if (translated != null) video.copy(overview = translated) else video
                        }
                    }
                }
                .awaitAll()
        }
    }

    private suspend fun translateText(
        text: String,
        targetLanguage: String,
        startedAtMs: Long
    ): String? {
        val target = normalizeLanguageCode(targetLanguage) ?: return null
        if (target !in TRANSLATION_SUPPORTED_LANGUAGES) return null
        val body = text.trim()
        if (body.length < TRANSLATION_MIN_TEXT_LENGTH || body.length > TRANSLATION_MAX_TEXT_LENGTH) {
            return null
        }
        val key = translationCacheKey(target, text)
        cachedValue(key)?.let { return it.ifBlank { null } }
        if (System.currentTimeMillis() - startedAtMs > BATCH_DEADLINE_MS) return null

        val detected = withTimeoutOrNull(DETECT_TIMEOUT_MS) {
            identifyLanguage(body)
        }
        if (detected == null) return null
        if (!shouldTranslateDetected(body, detected, target)) {
            rememberValue(key, null)
            return null
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(detected)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        try {
            val modelReady = withTimeoutOrNull(MODEL_TIMEOUT_MS) {
                translator.downloadModelIfNeeded().awaitOk()
            }
            if (modelReady != true) return null
            val translated = withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
                translator.translate(body).awaitText()
            }?.takeIf { it.isNotBlank() && it != body } ?: return null
            rememberValue(key, translated)
            return translated
        } finally {
            translator.close()
        }
    }

    private suspend fun identifyLanguage(text: String): String? =
        languageIdentifier.identifyLanguage(text).awaitText()

    private suspend fun cachedValue(key: String): String? = mutex.withLock {
        ensureLoadedLocked()[key]
    }

    private suspend fun rememberValue(key: String, value: String?) = mutex.withLock {
        ensureLoadedLocked()[key] = value ?: ""
        dirty = true
    }

    private suspend fun persistCacheIfDirty() {
        mutex.withLock {
            if (!dirty) return
            val snapshot = cache ?: return
            try {
                File(context.filesDir, CACHE_FILE).writeText(gson.toJson(snapshot))
                dirty = false
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write translation cache: ${e.message}")
            }
        }
    }

    private fun ensureLoadedLocked(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = readCacheFromDisk()
        cache = loaded
        return loaded
    }

    private fun readCacheFromDisk(): MutableMap<String, String> {
        val target: MutableMap<String, String> = newCache()
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (file.exists()) {
                val parsed: Map<String, String>? = gson.fromJson(
                    file.readText(),
                    object : TypeToken<Map<String, String>>() {}.type
                )
                parsed?.forEach { (entryKey, value) -> target[entryKey] = value }
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read translation cache: ${e.message}")
            newCache()
        }
    }

    private fun newCache(): MutableMap<String, String> =
        object : LinkedHashMap<String, String>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }

    private suspend fun Task<String>.awaitText(): String? = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
    }

    private suspend fun Task<Void>.awaitOk(): Boolean = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(true) }
        addOnFailureListener { if (continuation.isActive) continuation.resume(false) }
    }
}
