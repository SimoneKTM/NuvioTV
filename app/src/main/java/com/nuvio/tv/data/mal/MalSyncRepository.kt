package com.nuvio.tv.data.mal

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MAL_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
const val MAL_AUTOMATIC_REFRESH_INTERVAL_MS =
    MAL_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

fun shouldRunMalRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = MAL_AUTOMATIC_REFRESH_INTERVAL_MS
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true
    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

class MalRefreshGate {
    private val mutex = Mutex()
    @Volatile private var completionSequence = 0L
    private var lastCompletedProfileGeneration: Long? = null

    suspend fun runIfNeeded(
        profileGeneration: Long,
        shouldRun: () -> Boolean,
        block: suspend () -> Unit
    ) {
        val observedSequence = completionSequence
        mutex.withLock {
            if (
                completionSequence != observedSequence &&
                lastCompletedProfileGeneration == profileGeneration
            ) {
                return
            }
            if (!shouldRun()) return
            try {
                block()
            } finally {
                lastCompletedProfileGeneration = profileGeneration
                completionSequence += 1L
            }
        }
    }
}

@Singleton
class MalSyncRepository @Inject constructor(
    private val api: MalApi,
    private val storage: MalSyncStorage,
    private val authRepository: MalAuthRepository,
    private val authStorage: MalAuthStorage,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val refreshGate = MalRefreshGate()
    private val _state = MutableStateFlow(MalSyncState())
    private val _projection = MutableStateFlow(MalLibraryProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L

    val state: StateFlow<MalSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<MalLibraryProjection> = _projection.asStateFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = MalSyncState()
                    _projection.value = MalLibraryProjection.Empty
                    loadProfile(profileId)
                }
            }
        }
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        if (loadedProfileId != profileId) loadProfile(profileId)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        scope.launch { refresh(intent) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        refreshGate.runIfNeeded(
            profileGeneration = generation,
            shouldRun = {
                val current = _state.value
                profileId == profileManager.activeProfileId.value &&
                    generation == profileGeneration &&
                    authStorage.state.value.isAuthenticated &&
                    shouldRunMalRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = System.currentTimeMillis(),
                        hasError = current.errorMessage != null
                    )
            }
        ) {
            refreshSnapshot(profileId, generation)
        }
    }

    suspend fun clearCurrentProfile() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        profileGeneration += 1L
        storage.remove(profileId)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            _projection.value = MalLibraryProjection.Empty
            _state.value = MalSyncState(hasLoaded = true)
        }
    }

    suspend fun removeProfile(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == profileManager.activeProfileId.value) {
            clearCurrentProfile()
        } else {
            storage.remove(profileId)
        }
    }

    internal suspend fun commitMembershipChange(mediaId: Long, newStatus: MalMediaListStatus?) =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            val profileId = profileManager.activeProfileId.value
            val generation = profileGeneration
            snapshotMutex.withLock {
                if (!isCurrent(profileId, generation)) return@withLock
                val current = _state.value
                val snapshot = current.snapshot.applyStatusChange(mediaId, newStatus)
                if (snapshot == current.snapshot) return@withLock
                val projection = buildProjection(snapshot)
                storage.save(profileId, encodeSnapshot(snapshot))
                if (isCurrent(profileId, generation)) {
                    _projection.value = projection
                    _state.value = current.copy(snapshot = snapshot)
                }
            }
        }

    private suspend fun loadProfile(profileId: Int) = loadMutex.withLock {
        if (loadedProfileId == profileId) return@withLock
        val snapshot = storage.load(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { decodeSnapshot(payload) }
                    .onFailure { error -> Log.w(TAG, "Unable to decode MyAnimeList snapshot", error) }
                    .getOrNull()
            }
            ?: MalSyncSnapshot()
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            val projection = buildProjection(snapshot)
            _projection.value = projection
            _state.value = MalSyncState(snapshot = snapshot, hasLoaded = true)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            val token = authRepository.currentAccessToken()
            val userName = authStorage.state.value.username
            if (token == null || userName.isNullOrBlank()) {
                MalSyncSnapshot(lastCheckedAtEpochMs = previous.snapshot.lastCheckedAtEpochMs)
            } else {
                val items = fetchAllItems(token, userName)
                val ids = items.map { item -> item.id }
                if (ids.size != ids.distinct().size) {
                    Log.w(TAG, "MyAnimeList returned duplicate media ids; keeping first occurrence")
                }
                MalSyncSnapshot(
                    items = items,
                    lastCheckedAtEpochMs = System.currentTimeMillis()
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "MyAnimeList sync failed", error)
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync MyAnimeList"
                )
            }
            return@withLock
        }
        if (!isCurrent(profileId, generation)) return@withLock
        val projection = buildProjection(result)
        storage.save(profileId, encodeSnapshot(result))
        if (isCurrent(profileId, generation)) {
            _projection.value = projection
            _state.value = MalSyncState(snapshot = result, hasLoaded = true)
        }
    }

    private suspend fun fetchAllItems(token: String, userName: String): List<MalLibraryItem> {
        ensureLoaded()
        val items = mutableListOf<MalLibraryItem>()
        var offset = 0
        while (true) {
            val page = api.fetchMyAnimeList(
                token = token,
                userName = userName,
                offset = offset
            )
            val mapped = page.data.mapNotNull { entry ->
                val anime = entry.node ?: return@mapNotNull null
                val animeId = anime.id ?: return@mapNotNull null
                val listStatus = entry.listStatus ?: return@mapNotNull null
                val status = MalMediaListStatus.fromWireValue(listStatus.status)
                    ?: return@mapNotNull null
                MalLibraryItem(
                    id = animeId,
                    title = anime.title ?: animeId.toString(),
                    posterUrl = anime.mainPicture?.large ?: anime.mainPicture?.medium,
                    bannerUrl = null,
                    progress = listStatus.numEpisodesWatched ?: 0,
                    totalEpisodes = anime.numEpisodes,
                    score = listStatus.score?.takeIf { it > 0 },
                    status = status,
                    updatedAt = parseMalTimestamp(listStatus.updatedAt)
                )
            }
            items.addAll(mapped)
            if (page.paging?.next == null || mapped.size < MAL_PAGE_LIMIT) break
            offset += MAL_PAGE_LIMIT
        }
        return items
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    private fun buildProjection(snapshot: MalSyncSnapshot): MalLibraryProjection =
        snapshot.toMalLibraryProjection(tabTitle = { resId -> context.getString(resId) })

    private suspend fun decodeSnapshot(payload: String): MalSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: MalSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "MalSync"
        const val MAL_PAGE_LIMIT = 1_000
    }
}

private fun MalSyncSnapshot.applyStatusChange(
    mediaId: Long,
    newStatus: MalMediaListStatus?
): MalSyncSnapshot {
    val updatedItems = if (newStatus == null) {
        items.filterNot { item -> item.id == mediaId }
    } else {
        items.map { item ->
            if (item.id == mediaId) item.copy(status = newStatus) else item
        }
    }
    return copy(items = updatedItems)
}

internal fun parseMalTimestamp(value: String?): Long {
    val trimmed = value?.trim() ?: return 0L
    return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrElse { 0L }
}