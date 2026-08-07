package com.nuvio.tv.data.kitsu

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
import java.time.Instant

const val KITSU_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
const val KITSU_AUTOMATIC_REFRESH_INTERVAL_MS =
    KITSU_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

fun shouldRunKitsuRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = KITSU_AUTOMATIC_REFRESH_INTERVAL_MS
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true
    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

class KitsuRefreshGate {
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
class KitsuSyncRepository @Inject constructor(
    private val api: KitsuApi,
    private val storage: KitsuSyncStorage,
    private val authRepository: KitsuAuthRepository,
    private val authStorage: KitsuAuthStorage,
    private val profileManager: ProfileManager,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val refreshGate = KitsuRefreshGate()
    private val _state = MutableStateFlow(KitsuSyncState())
    private val _projection = MutableStateFlow(KitsuLibraryProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L

    val state: StateFlow<KitsuSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<KitsuLibraryProjection> = _projection.asStateFlow()

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = KitsuSyncState()
                    _projection.value = KitsuLibraryProjection.Empty
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
                    shouldRunKitsuRefresh(
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
            _projection.value = KitsuLibraryProjection.Empty
            _state.value = KitsuSyncState(hasLoaded = true)
        }
    }

    suspend fun removeProfile(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == profileManager.activeProfileId.value) {
            clearCurrentProfile()
        } else {
            storage.remove(profileId)
        }
    }

    internal suspend fun commitMembershipChange(mediaId: Long, newStatus: KitsuMediaListStatus?) =
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
                    .onFailure { error -> Log.w(TAG, "Unable to decode Kitsu snapshot", error) }
                    .getOrNull()
            }
            ?: KitsuSyncSnapshot()
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            val projection = buildProjection(snapshot)
            _projection.value = projection
            _state.value = KitsuSyncState(snapshot = snapshot, hasLoaded = true)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            val userId = authRepository.state.value.userId
            val token = authStorage.accessToken()
            if (userId == null || token == null) {
                KitsuSyncSnapshot(lastCheckedAtEpochMs = previous.snapshot.lastCheckedAtEpochMs)
            } else {
                val items = fetchAllItems(token, userId)
                val ids = items.map { item -> item.id }
                if (ids.size != ids.distinct().size) {
                    Log.w(TAG, "Kitsu returned duplicate media ids; keeping first occurrence")
                }
                KitsuSyncSnapshot(
                    items = items,
                    lastCheckedAtEpochMs = System.currentTimeMillis()
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Kitsu sync failed", error)
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync Kitsu"
                )
            }
            return@withLock
        }
        if (!isCurrent(profileId, generation)) return@withLock
        val projection = buildProjection(result)
        storage.save(profileId, encodeSnapshot(result))
        if (isCurrent(profileId, generation)) {
            _projection.value = projection
            _state.value = KitsuSyncState(snapshot = result, hasLoaded = true)
        }
    }

    private suspend fun fetchAllItems(token: String, userId: Long): List<KitsuLibraryItem> {
        ensureLoaded()
        val items = mutableListOf<KitsuLibraryItem>()
        var offset = 0
        while (true) {
            val response = api.fetchLibraryEntries(token = token, userId = userId, pageOffset = offset)
            val includedMap = response.included?.associateBy { included -> included.id } ?: emptyMap()
            val page = response.data.mapNotNull { entry ->
                val attrs = entry.attributes ?: return@mapNotNull null
                val animeId = entry.relationships?.anime?.data?.id
                    ?: entry.relationships?.media?.data?.id
                    ?: return@mapNotNull null
                val mediaId = animeId.toLongOrNull() ?: return@mapNotNull null
                val anime = includedMap[animeId]?.attributes
                val title = anime?.titles?.en
                    ?: anime?.titles?.enJp
                    ?: anime?.titles?.canonical
                    ?: anime?.slug
                    ?: mediaId.toString()
                val status = KitsuMediaListStatus.fromWireValue(attrs.status)
                    ?: KitsuMediaListStatus.CURRENT
                KitsuLibraryItem(
                    id = mediaId,
                    entryId = entry.id,
                    title = title,
                    posterUrl = anime?.posterImage?.medium
                        ?: anime?.posterImage?.small
                        ?: anime?.posterImage?.large,
                    bannerUrl = null,
                    progress = attrs.progress ?: 0,
                    totalEpisodes = anime?.episodeCount,
                    rating = attrs.rating ?: attrs.ratingTwenty?.div(4.0),
                    status = status,
                    updatedAt = parseKitsuTimestamp(attrs.updatedAt)
                )
            }
            items.addAll(page)
            if (response.links?.next == null || page.size < 500) break
            offset += 500
        }
        return items
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    private fun buildProjection(snapshot: KitsuSyncSnapshot): KitsuLibraryProjection =
        snapshot.toKitsuLibraryProjection(tabTitle = { resId -> context.getString(resId) })

    private suspend fun decodeSnapshot(payload: String): KitsuSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: KitsuSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "KitsuSync"
    }
}

private fun KitsuSyncSnapshot.applyStatusChange(
    mediaId: Long,
    newStatus: KitsuMediaListStatus?
): KitsuSyncSnapshot {
    val updatedItems = if (newStatus == null) {
        items.filterNot { item -> item.id == mediaId }
    } else {
        items.map { item ->
            if (item.id == mediaId) item.copy(status = newStatus) else item
        }
    }
    return copy(items = updatedItems)
}

internal fun parseKitsuTimestamp(value: String?): Long {
    val trimmed = value?.trim() ?: return 0L
    return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrElse { 0L }
}