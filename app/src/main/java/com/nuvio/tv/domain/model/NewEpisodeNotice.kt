package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * A newly aired episode of a show that is saved in the user's library.
 */
@Immutable
data class NewEpisodeNotice(
    val contentId: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val poster: String?,
    val logo: String?,
    val airedLabel: String?,
    val airedAtMs: Long
)
