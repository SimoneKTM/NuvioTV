package com.nuvio.tv

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.StrictMode
import android.util.Log
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var startupHomePreloader: com.nuvio.tv.core.sync.StartupHomePreloader
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var trailerPlayerPool: com.nuvio.tv.core.player.TrailerPlayerPool

    companion object {
        private const val TAG = "NuvioApplication"
        /** Let the splash/profile render before background startup work begins. */
        private const val STARTUP_DEFERRED_INIT_DELAY_MS = 1_500L
        private const val STARTUP_CHANNEL_SYNC_DELAY_MS = 3_000L
        private const val STARTUP_HOME_PRELOAD_DELAY_MS = 4_500L
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""

        // In-process memory telemetry (Log.i, release-safe): captures the Java/
        // Native/Graphics curve every 10s even when dumpsys times out under
        // allocation bursts — the LMK kills left us blind until now.
        runCatching { com.nuvio.tv.core.diagnostics.MemoryWatch.start() }

        // Governor di pressione: lmkd avvisa (onTrimMemory livello 15) solo
        // all'emergenza assoluta, e nei kill documentati l'avviso non arrivava
        // mai in tempo. MemoryWatch legge MemAvailable/SwapFree dal sistema
        // ogni 10s e chiama questo callback appena entrano in zona di kill:
        // liberiamo trailer + cache ~1 minuto prima che il processo venga
        // sacrificato, quando la liberatoria è ancora efficace.
        runCatching {
            com.nuvio.tv.core.diagnostics.MemoryWatch.onRelief = ::relieveMemoryPressure
        }

        // Warm the Fluid Mode zoom mirror for the same reason as the locale above:
        // MainActivity reads it in attachBaseContext to decide the density scale.
        LayoutPreferenceDataStore.readFluidZoomMirror(this)

        // Defer heavy initialization until the first frames are on screen,
        // and stagger it: firing Sentry I/O, the JobScheduler binder call and
        // the home preloader (ML Kit + catalog warm-up) in one shot recreates
        // the launch freeze we just removed, so each wave gets its own slot.
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(
            {
                SentryInitializer.start(this, sentrySettingsDataStore)
                PluginRuntimeHooks.onApplicationCreate(this)
            },
            STARTUP_DEFERRED_INIT_DELAY_MS
        )
        handler.postDelayed(
            { androidTvChannelSyncService.start() },
            STARTUP_CHANNEL_SYNC_DELAY_MS
        )
        handler.postDelayed(
            // Warm Home catalogs/CW/layout while the splash + profile UI is up.
            { startupHomePreloader.ensureStarted() },
            STARTUP_HOME_PRELOAD_DELAY_MS
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Log.i sopravvive nel release: senza questo non era possibile capire se il
        // sistema aveva davvero avvisato l'app prima del kill LMK.
        Log.i(TAG, "onTrimMemory level=$level poolInitialized=${this::trailerPlayerPool.isInitialized}")
        if (!this::trailerPlayerPool.isInitialized) return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> relieveMemoryPressure("lmkd-level$level")
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                runCatching { trailerPlayerPool.trim() }
            }
        }
    }

    /**
     * Percorso unico di liberatoria di memoria. Usato dal governor di pressione
     * di MemoryWatch (autonomo, ~1 minuto prima del kill) e da onTrimMemory
     * (segnale di lmkd). Il trailer è la risorsa più grande che possiamo
     * rilasciare su richiesta: il trim misurato nel release è ~200 MB di heap
     * nativo; la cache immagini (16 MB) si ricostruisce dal disco.
     */
    private fun relieveMemoryPressure(reason: String) {
        Log.w(TAG, "relieveMemoryPressure($reason)")
        if (!this::trailerPlayerPool.isInitialized) return
        runCatching { trailerPlayerPool.trim() }
        runCatching { SingletonImageLoader.get(this).memoryCache?.clear() }
        runCatching { System.gc() }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // CacheControlCacheStrategy respects server Cache-Control headers,
                // so dynamic images (e.g. BetterPosters with max-age) revalidate.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                // OkHttp defaults are 64 in-flight requests and 5 per host;
                                // a single Home load was holding 6 parallel connections to
                                // aiometadata.elfhosted.com and 46 live OkHttp threads. Image
                                // fetches are the bulk of that traffic, so cap them here.
                                .dispatcher(
                                    okhttp3.Dispatcher().apply {
                                        maxRequests = 8
                                        maxRequestsPerHost = 2
                                    }
                                )
                                // OkHttp defaults to 10s: poster/backdrop fetches on
                                // slow sticks frequently exceed it, leaving blank cards.
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                        },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
            }
            .memoryCache {
                // Bitmap pixels count against the Java heap on the Fire Stick (verified
                // with a heap dump), so the old "33% of maxMemory" budget alone pushed
                // the app past 250 MB and triggered low-memory kills. Fixed cap instead.
                // The stick only has 922 MB of RAM and the app was reaching 646 MB at the
                // Low Memory Killer threshold, so 32 MB of resident bitmaps on top of the
                // video/GPU buffers is more than it can afford: 16 MB keeps a full screen
                // of posters plus the hero backdrop hot and lets the rest fall back to the
                // 200 MB disk cache.
                MemoryCache.Builder()
                    .maxSizeBytes(16L * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            .allowRgb565(true)
            .bitmapFactoryMaxParallelism(2)
            .build()
    }
}
