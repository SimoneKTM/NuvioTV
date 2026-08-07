package com.nuvio.tv.data.anilist

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import dagger.hilt.android.qualifiers.ApplicationContext
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

const val ANILIST_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
const val ANILIST_AUTOMATIC_REFRESH_INTERVAL_MS =
    ANILIST_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

fun shouldRunAniListRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = ANILIST_AUTOMATIC_REFRESH_INTERVAL_MS
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true
    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

class AniListRefreshGate {
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
class AniListSyncRepository @Inject constructor(
    private val api: AniListApi,
    private val storage: AniListSyncStorage,
    private val authRepository: AniListAuthRepository,
    private val authStorage: AniListAuthStorage,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val refreshGate = AniListRefreshGate()
    private val _state = MutableStateFlow(AniListSyncState())
    private val _projection = MutableStateFlow(AniListLibraryProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L

    val state: StateFlow<AniListSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<AniListLibraryProjection> = _projection.asStateFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = AniListSyncState()
                    _projection.value = AniListLibraryProjection.Empty
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
                    shouldRunAniListRefresh(
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
            _projection.value = AniListLibraryProjection.Empty
            _state.value = AniListSyncState(hasLoaded = true)
        }
    }

    suspend fun removeProfile(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == profileManager.activeProfileId.value) {
            clearCurrentProfile()
        } else {
            storage.remove(profileId)
        }
    }

    internal suspend fun commitMembershipChange(mediaId: Long, newStatus: AniListMediaListStatus?) =
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

    internal suspend fun commitProgressChange(
        mediaId: Long,
        progress: Int,
        status: AniListMediaListStatus? = null
    ) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyProgressChange(mediaId, progress, status)
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
                    .onFailure { error -> Log.w(TAG, "Unable to decode AniList snapshot", error) }
                    .getOrNull()
            }
            ?: AniListSyncSnapshot()
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            val projection = buildProjection(snapshot)
            _projection.value = projection
            _state.value = AniListSyncState(snapshot = snapshot, hasLoaded = true)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            val userId = authRepository.state.value.userId
            val token = authStorage.accessToken()
            if (userId == null || token == null) {
                AniListSyncSnapshot(lastCheckedAtEpochMs = previous.snapshot.lastCheckedAtEpochMs)
            } else {
                val items = api.fetchMediaListCollection(userId = userId, token = token)
                val ids = items.map { item -> item.id }
                if (ids.size != ids.distinct().size) {
                    Log.w(TAG, "AniList returned duplicate media ids; keeping first occurrence")
                }
                AniListSyncSnapshot(
                    items = items,
                    lastCheckedAtEpochMs = System.currentTimeMillis()
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "AniList sync failed", error)
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync AniList"
                )
            }
            return@withLock
        }
        if (!isCurrent(profileId, generation)) return@withLock
        val projection = buildProjection(result)
        storage.save(profileId, encodeSnapshot(result))
        if (isCurrent(profileId, generation)) {
            _projection.value = projection
            _state.value = AniListSyncState(snapshot = result, hasLoaded = true)
        }
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    private fun buildProjection(snapshot: AniListSyncSnapshot): AniListLibraryProjection =
        snapshot.toAniListLibraryProjection(tabTitle = { resId -> context.getString(resId) })

    private suspend fun decodeSnapshot(payload: String): AniListSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: AniListSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "AniListSync"
    }
}

private fun AniListSyncSnapshot.applyStatusChange(
    mediaId: Long,
    newStatus: AniListMediaListStatus?
): AniListSyncSnapshot {
    val updatedItems = if (newStatus == null) {
        items.filterNot { item -> item.id == mediaId }
    } else {
        items.map { item ->
            if (item.id == mediaId) item.copy(status = newStatus) else item
        }
    }
    return copy(items = updatedItems)
}

private fun AniListSyncSnapshot.applyProgressChange(
    mediaId: Long,
    progress: Int,
    status: AniListMediaListStatus?
): AniListSyncSnapshot {
    val normalized = progress.coerceAtLeast(0)
    val updatedItems = items.map { item ->
        if (item.id != mediaId) return@map item
        val newStatus = status ?: item.status
        if (item.progress == normalized && item.status == newStatus) {
            item
        } else {
            item.copy(
                progress = normalized,
                status = newStatus,
                updatedAt = System.currentTimeMillis() / 1_000L
            )
        }
    }
    return copy(items = updatedItems)
}