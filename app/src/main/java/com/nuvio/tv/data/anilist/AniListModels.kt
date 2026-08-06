package com.nuvio.tv.data.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class AniListMediaType(val wireValue: String) {
    ANIME("ANIME"),
    MANGA("MANGA")
}

enum class AniListMediaListStatus(val wireValue: String) {
    CURRENT("CURRENT"),
    PLANNING("PLANNING"),
    COMPLETED("COMPLETED"),
    PAUSED("PAUSED"),
    DROPPED("DROPPED"),
    REPEATING("REPEATING");

    companion object {
        fun fromWireValue(value: String?): AniListMediaListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

/** A single anime (or manga) entry from the user's AniList library. */
@Serializable
data class AniListLibraryItem(
    val id: Long,
    val entryId: Long? = null,
    val title: String,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val score: Int? = null,
    val status: AniListMediaListStatus,
    val updatedAt: Long = 0L
)

@Serializable
internal data class AniListGraphQLRequest(
    val query: String,
    val variables: JsonObject? = null
)

@Serializable
internal data class AniListUserResponse(
    val data: AniListUserData? = null
)

@Serializable
internal data class AniListUserData(
    @SerialName("Viewer") val viewer: AniListViewer? = null
)

@Serializable
data class AniListViewer(
    val id: Long,
    val name: String? = null
)

@Serializable
internal data class AniListCollectionResponse(
    val data: AniListCollectionData? = null
)

@Serializable
internal data class AniListCollectionData(
    @SerialName("MediaListCollection") val collection: AniListMediaListCollection? = null
)

@Serializable
internal data class AniListMediaListCollection(
    val lists: List<AniListMediaListGroup>? = null
)

@Serializable
internal data class AniListMediaListGroup(
    val status: String? = null,
    val entries: List<AniListMediaListEntry>? = null
)

@Serializable
internal data class AniListMediaListEntry(
    val id: Long? = null,
    val status: String? = null,
    val progress: Int = 0,
    val score: Int? = null,
    val updatedAt: Long = 0L,
    val media: AniListMediaNode? = null
)

@Serializable
internal data class AniListMediaNode(
    val id: Long,
    val idMal: Long? = null,
    val episodes: Int? = null,
    val title: AniListTitle? = null,
    val coverImage: AniListCoverImage? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val startDate: AniListDate? = null
)

@Serializable
internal data class AniListTitle(
    val english: String? = null,
    val romaji: String? = null,
    val userPreferred: String? = null
)

@Serializable
internal data class AniListCoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null
)

@Serializable
internal data class AniListDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null
)

@Serializable
internal data class AniListSaveMediaListEntryResponse(
    val data: AniListSaveMediaListEntryData? = null
)

@Serializable
internal data class AniListSaveMediaListEntryData(
    @SerialName("SaveMediaListEntry") val entry: AniListMediaListEntry? = null
)

@Serializable
internal data class AniListDeleteMediaListEntryResponse(
    val data: AniListDeleteMediaListEntryData? = null
)

@Serializable
internal data class AniListDeleteMediaListEntryData(
    @SerialName("DeleteMediaListEntry") val deleted: Boolean? = null
)

@Serializable
internal data class AniListErrorEnvelope(
    val errors: List<AniListGraphQLError>? = null
)

@Serializable
internal data class AniListGraphQLError(
    val message: String? = null
)