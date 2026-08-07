package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.anilist.AniListAuthStorage
import com.nuvio.tv.data.kitsu.KitsuAuthStorage
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.AnimeTvdbSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TvdbSettingsDataStore
import com.nuvio.tv.data.mal.MalAuthStorage
import com.nuvio.tv.data.remote.supabase.SupabaseProviderCredential
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.MDBListSettings
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val PROVIDER_CREDENTIAL_TAG = "ProviderCredentialSync"
private const val PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS = 500L
private const val PROVIDER_CREDENTIAL_FOREGROUND_DELAY_MS = 2500L
private const val PROVIDER_CREDENTIAL_FOREGROUND_MIN_INTERVAL_MS = 60_000L
private const val API_KEY_FIELD = "api_key"
private const val CLIENT_ID_FIELD = "client_id"
private const val TRACKER_TOKEN_FIELD = "credential_json"
private const val TRACKER_ACCESS_TOKEN_FIELD = "access_token"
private const val TRACKER_REFRESH_TOKEN_FIELD = "refresh_token"

private data class ProviderCredentialScope(
    val userId: String,
    val profileId: Int
)

private data class TrackerCredentialsAndTvdb(
    val debrid: DebridSettings,
    val mdbList: MDBListSettings,
    val animeSkipClientId: String,
    val tvdb: ProviderCredentialValue,
    val animeTvdb: ProviderCredentialValue
)

@Singleton
class ProviderCredentialSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    private val animeTvdbSettingsDataStore: AnimeTvdbSettingsDataStore,
    private val malAuthStorage: MalAuthStorage,
    private val aniListAuthStorage: AniListAuthStorage,
    private val kitsuAuthStorage: KitsuAuthStorage,
    private val syncClientIdentity: SyncClientIdentity
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val stateLock = Any()
    private val observedSnapshots = mutableMapOf<Int, ProviderCredentialSnapshot>()
    private val baselineSnapshots = mutableMapOf<ProviderCredentialScope, ProviderCredentialSnapshot>()
    private val pendingScopes = mutableSetOf<ProviderCredentialScope>()
    private var foregroundPullJob: Job? = null
    private var lastForegroundPullAtMs: Long = 0L

    init {
        observeLocalCredentials()
        observeAuthState()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(error)) throw error
            block()
        }
    }

    suspend fun syncFromRemote(
        profileId: Int = profileManager.activeProfileId.value
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val credentialScope = currentScope(profileId) ?: return@withLock Result.success(false)
                val localSnapshot = currentSnapshot(profileId)
                val shouldPush = synchronized(stateLock) {
                    val baseline = baselineSnapshots.getOrPut(credentialScope) {
                        observedSnapshots[profileId] ?: localSnapshot
                    }
                    credentialScope in pendingScopes || baseline != localSnapshot
                }
                if (shouldPush) {
                    pushSnapshot(localSnapshot)
                    synchronized(stateLock) {
                        pendingScopes.remove(credentialScope)
                        baselineSnapshots[credentialScope] = localSnapshot
                    }
                }

                seedSnapshot(localSnapshot)
                val rows = pullRows(profileId)
                requireCurrentScope(credentialScope)
                val remoteSnapshot = localSnapshot.mergeRemote(rows)
                val applied = remoteSnapshot != localSnapshot
                if (applied) {
                    applySnapshot(remoteSnapshot)
                }
                requireCurrentScope(credentialScope)
                synchronized(stateLock) {
                    observedSnapshots[profileId] = remoteSnapshot
                    baselineSnapshots[credentialScope] = remoteSnapshot
                    pendingScopes.remove(credentialScope)
                }
                lastForegroundPullAtMs = SystemClock.elapsedRealtime()
                Log.d(
                    PROVIDER_CREDENTIAL_TAG,
                    "Synchronized ${remoteSnapshot.values.size} credentials for profile $profileId applied=$applied"
                )
                Result.success(applied)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(PROVIDER_CREDENTIAL_TAG, "Failed to synchronize provider credentials", error)
                Result.failure(error)
            }
        }
    }

    fun requestForegroundPull(force: Boolean = false) {
        if (!authManager.isAuthenticated) return
        val now = SystemClock.elapsedRealtime()
        if (!force && foregroundPullJob?.isActive == true) return
        if (!force && now - lastForegroundPullAtMs < PROVIDER_CREDENTIAL_FOREGROUND_MIN_INTERVAL_MS) return

        foregroundPullJob = scope.launch {
            if (!force) {
                delay(PROVIDER_CREDENTIAL_FOREGROUND_DELAY_MS)
            }
            if (!authManager.isAuthenticated) return@launch
            syncFromRemote()
        }
    }

    private suspend fun seedSnapshot(snapshot: ProviderCredentialSnapshot) {
        val params = credentialParams(snapshot)
        withJwtRefreshRetry {
            postgrest.rpc("sync_seed_provider_credentials", params)
        }
    }

    private suspend fun pushSnapshot(snapshot: ProviderCredentialSnapshot) {
        val params = credentialParams(snapshot)
        withJwtRefreshRetry {
            postgrest.rpc("sync_push_provider_credentials", params)
        }
        Log.d(
            PROVIDER_CREDENTIAL_TAG,
            "Pushed ${snapshot.values.size} credentials for profile ${snapshot.profileId}"
        )
    }

    private suspend fun pullRows(profileId: Int): List<SupabaseProviderCredential> {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
        }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_provider_credentials", params)
        }
        return response.decodeList()
    }

    private fun credentialParams(snapshot: ProviderCredentialSnapshot) = buildJsonObject {
        put("p_profile_id", snapshot.profileId)
        put("p_credentials", buildJsonArray {
            snapshot.values.forEach { credential ->
                addJsonObject {
                    put("provider", credential.provider)
                    put("credential_json", credential.credentialJson())
                }
            }
        })
        putSyncOriginClientId(syncClientIdentity)
    }

    private suspend fun currentSnapshot(profileId: Int): ProviderCredentialSnapshot {
        check(profileManager.activeProfileId.value == profileId)
        val debrid = debridSettingsDataStore.settings.first()
        val mdbList = mdbListSettingsDataStore.settings.first()
        val animeSkipClientId = animeSkipSettingsDataStore.clientId.first()
        val tvdb = tvdbSettingsDataStore.settings.first()
        val animeTvdb = animeTvdbSettingsDataStore.settings.first()
        check(profileManager.activeProfileId.value == profileId)
        return ProviderCredentialSnapshot(
            profileId = profileId,
            values = buildList {
                DebridProviders.all().forEach { provider ->
                    add(
                        ProviderCredentialValue(
                            provider = ProviderCredentialIds.debrid(provider.id),
                            field = API_KEY_FIELD,
                            value = debrid.apiKeyFor(provider.id)
                        )
                    )
                }
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.MDBLIST,
                        field = API_KEY_FIELD,
                        value = mdbList.apiKey
                    )
                )
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.ANIMESKIP,
                        field = CLIENT_ID_FIELD,
                        value = animeSkipClientId
                    )
                )
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.TVDB,
                        field = API_KEY_FIELD,
                        value = tvdb.apiKey
                    )
                )
                add(
                    ProviderCredentialValue(
                        provider = ProviderCredentialIds.ANIMETVDB,
                        field = API_KEY_FIELD,
                        value = animeTvdb.apiKey
                    )
                )
            } + trackerCredentials()
        )
    }

    private fun trackerCredentials(): List<ProviderCredentialValue> = buildList {
        add(
            ProviderCredentialValue(
                provider = ProviderCredentialIds.MAL,
                field = TRACKER_TOKEN_FIELD,
                jsonValue = buildJsonObject {
                    put("access_token", malAuthStorage.accessToken().orEmpty())
                    malAuthStorage.refreshToken()?.takeIf(String::isNotBlank)?.let {
                        put("refresh_token", it)
                    }
                }
            )
        )
        add(
            ProviderCredentialValue(
                provider = ProviderCredentialIds.ANILIST,
                field = TRACKER_TOKEN_FIELD,
                jsonValue = buildJsonObject {
                    put("access_token", aniListAuthStorage.accessToken().orEmpty())
                }
            )
        )
        add(
            ProviderCredentialValue(
                provider = ProviderCredentialIds.KITSU,
                field = TRACKER_TOKEN_FIELD,
                jsonValue = buildJsonObject {
                    put("access_token", kitsuAuthStorage.accessToken().orEmpty())
                }
            )
        )
    }

    private suspend fun applySnapshot(snapshot: ProviderCredentialSnapshot) {
        snapshot.values.forEach { credential ->
            check(profileManager.activeProfileId.value == snapshot.profileId)
            when {
                credential.provider.startsWith("debrid:") -> {
                    val providerId = credential.provider.substringAfter("debrid:")
                    debridSettingsDataStore.setProviderApiKey(providerId, credential.value)
                }
                credential.provider == ProviderCredentialIds.MDBLIST -> {
                    mdbListSettingsDataStore.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.ANIMESKIP -> {
                    animeSkipSettingsDataStore.setClientId(credential.value)
                }
                credential.provider == ProviderCredentialIds.TVDB -> {
                    tvdbSettingsDataStore.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.ANIMETVDB -> {
                    animeTvdbSettingsDataStore.setApiKey(credential.value)
                }
                credential.provider == ProviderCredentialIds.MAL -> {
                    val token = credential.jsonValue?.get(TRACKER_ACCESS_TOKEN_FIELD)
                        ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (token.isBlank()) {
                        malAuthStorage.clearAuth(scope = malAuthStorage.currentScope())
                    } else {
                        malAuthStorage.completeAuthorization(
                            token = token,
                            refreshToken = credential.jsonValue?.get(TRACKER_REFRESH_TOKEN_FIELD)
                                ?.jsonPrimitive?.contentOrNull,
                            scope = malAuthStorage.currentScope()
                        )
                    }
                }
                credential.provider == ProviderCredentialIds.ANILIST -> {
                    val token = credential.jsonValue?.get(TRACKER_ACCESS_TOKEN_FIELD)
                        ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (token.isBlank()) {
                        aniListAuthStorage.clearAuth(scope = aniListAuthStorage.currentScope())
                    } else {
                        aniListAuthStorage.completeTokenAuthorization(
                            token = token,
                            scope = aniListAuthStorage.currentScope()
                        )
                    }
                }
                credential.provider == ProviderCredentialIds.KITSU -> {
                    val token = credential.jsonValue?.get(TRACKER_ACCESS_TOKEN_FIELD)
                        ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (token.isBlank()) {
                        kitsuAuthStorage.clearAuth(scope = kitsuAuthStorage.currentScope())
                    } else {
                        kitsuAuthStorage.completeTokenAuthorization(
                            token = token,
                            scope = kitsuAuthStorage.currentScope()
                        )
                    }
                }
            }
        }
    }

    private fun currentScope(profileId: Int): ProviderCredentialScope? {
        val state = authManager.authState.value as? AuthState.FullAccount ?: return null
        if (profileManager.activeProfileId.value != profileId) return null
        return ProviderCredentialScope(state.userId, profileId)
    }

    private fun requireCurrentScope(expected: ProviderCredentialScope) {
        if (currentScope(expected.profileId) != expected) {
            throw CancellationException("Provider credential sync target changed")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun observeLocalCredentials() {
        scope.launch {
            profileManager.activeProfileId
                .flatMapLatest { profileId ->
                    combine(
                        combine(
                            debridSettingsDataStore.settings,
                            mdbListSettingsDataStore.settings,
                            animeSkipSettingsDataStore.clientId,
                            tvdbSettingsDataStore.settings,
                            animeTvdbSettingsDataStore.settings
                        ) { debrid, mdbList, animeSkipClientId, tvdb, animeTvdb ->
                            val tvdbSettings = ProviderCredentialValue(
                                provider = ProviderCredentialIds.TVDB,
                                field = API_KEY_FIELD,
                                value = tvdb.apiKey
                            )
                            val animeTvdbSettings = ProviderCredentialValue(
                                provider = ProviderCredentialIds.ANIMETVDB,
                                field = API_KEY_FIELD,
                                value = animeTvdb.apiKey
                            )
                            TrackerCredentialsAndTvdb(
                                debrid = debrid,
                                mdbList = mdbList,
                                animeSkipClientId = animeSkipClientId,
                                tvdb = tvdbSettings,
                                animeTvdb = animeTvdbSettings
                            )
                        },
                        malAuthStorage.state,
                        aniListAuthStorage.state,
                        kitsuAuthStorage.state
                    ) { settings, _, _, _ ->
                        ProviderCredentialSnapshot(
                            profileId = profileId,
                            values = buildList {
                                DebridProviders.all().forEach { provider ->
                                    add(
                                        ProviderCredentialValue(
                                            provider = ProviderCredentialIds.debrid(provider.id),
                                            field = API_KEY_FIELD,
                                            value = settings.debrid.apiKeyFor(provider.id)
                                        )
                                    )
                                }
                                add(
                                    ProviderCredentialValue(
                                        provider = ProviderCredentialIds.MDBLIST,
                                        field = API_KEY_FIELD,
                                        value = settings.mdbList.apiKey
                                    )
                                )
                                add(
                                    ProviderCredentialValue(
                                        provider = ProviderCredentialIds.ANIMESKIP,
                                        field = CLIENT_ID_FIELD,
                                        value = settings.animeSkipClientId
                                    )
                                )
                                add(settings.tvdb)
                                add(settings.animeTvdb)
                            } + trackerCredentials()
                        )
                    }
                }
                .distinctUntilChanged()
                .debounce(PROVIDER_CREDENTIAL_PUSH_DEBOUNCE_MS)
                .collect { snapshot ->
                    handleLocalSnapshot(snapshot)
                }
        }
    }

    private suspend fun handleLocalSnapshot(snapshot: ProviderCredentialSnapshot) {
        syncMutex.withLock {
            val previous = synchronized(stateLock) {
                observedSnapshots.put(snapshot.profileId, snapshot)
            }
            val credentialScope = currentScope(snapshot.profileId) ?: return
            if (previous == null) {
                synchronized(stateLock) {
                    baselineSnapshots.putIfAbsent(credentialScope, snapshot)
                }
                return
            }
            if (previous == snapshot) return
            val baseline = synchronized(stateLock) {
                baselineSnapshots.getOrPut(credentialScope) { previous }
            }
            if (baseline == snapshot) return

            try {
                pushSnapshot(snapshot)
                synchronized(stateLock) {
                    baselineSnapshots[credentialScope] = snapshot
                    pendingScopes.remove(credentialScope)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                synchronized(stateLock) {
                    pendingScopes.add(credentialScope)
                }
                Log.e(
                    PROVIDER_CREDENTIAL_TAG,
                    "Failed to push local provider credential changes for profile ${snapshot.profileId}",
                    error
                )
            }
        }
    }

    private fun observeAuthState() {
        scope.launch {
            authManager.authState.collect { state ->
                if (state is AuthState.FullAccount) return@collect
                synchronized(stateLock) {
                    observedSnapshots.clear()
                    baselineSnapshots.clear()
                    pendingScopes.clear()
                }
            }
        }
    }
}
