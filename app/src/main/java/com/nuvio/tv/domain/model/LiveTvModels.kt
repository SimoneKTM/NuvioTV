package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LiveTvPlaylist(
    val id: String,
    val sourceUrl: String,
    val name: String
)

@Immutable
data class LiveTvChannel(
    val id: String,
    val name: String,
    val group: String? = null,
    val logo: String? = null,
    val streamUrl: String
)