package com.nuvio.tv.data.repository

internal enum class AddonReconcileMode {
    /** Startup / foreground / realtime pulls: add missing addons but never remove local ones. */
    MERGE_KEEP_LOCAL,

    /** Explicit user actions (manual sync, sign-in, claim): adopt the remote list as-is. */
    ADOPT_REMOTE
}

internal data class AddonReconcilePlan(
    val finalUrls: List<String>,
    val removedUrls: List<String>
)

/**
 * Pure decision helper for reconciling the locally installed addon list with the
 * remote snapshot. URLs are expected to already be canonicalized by the caller.
 *
 * - MERGE_KEEP_LOCAL keeps every local entry and only appends remote-only URLs.
 * - ADOPT_REMOTE replaces the local list with the remote one, except when the remote
 *   list is empty while the local one is not (a stale remote must never wipe config).
 */
internal fun planAddonReconcile(
    localUrls: List<String>,
    remoteUrls: List<String>,
    mode: AddonReconcileMode
): AddonReconcilePlan {
    val localByNormalized = linkedMapOf<String, String>()
    localUrls.forEach { url ->
        localByNormalized.putIfAbsent(url.lowercase(), url)
    }
    val remote = remoteUrls.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    val remoteSet = remote.map { it.lowercase() }.toSet()

    val remoteIsEmptyStaleGuard = remote.isEmpty() && localUrls.isNotEmpty()
    val removeMissing = mode == AddonReconcileMode.ADOPT_REMOTE && !remoteIsEmptyStaleGuard

    val remoteOrdered = remote.map { remoteUrl ->
        localByNormalized[remoteUrl.lowercase()] ?: remoteUrl
    }

    val extras = localUrls.filter { it.lowercase() !in remoteSet }
    val finalUrls = if (removeMissing) remoteOrdered else remoteOrdered + extras
    val removedUrls = if (removeMissing) localUrls.filter { it.lowercase() !in remoteSet } else emptyList()

    return AddonReconcilePlan(finalUrls = finalUrls, removedUrls = removedUrls)
}
