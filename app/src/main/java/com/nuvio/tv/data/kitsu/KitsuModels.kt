package com.nuvio.tv.data.kitsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class KitsuMediaListStatus(val wireValue: String) {
    CURRENT("current"),
    PLANNED("planned"),
    COMPLETED("completed"),
    ON_HOLD("on_hold"),
    DROPPED("dropped");

    companion object {
        fun fromWireValue(value: String?): KitsuMediaListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

/** A single anime entries from the user's Kitsu library. */
@Serializable
data class KitsuLibraryItem(
    val id: Long,
    val entryId: String? = null,
    val title: String,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val rating: Double? = null,
    val status: KitsuMediaListStatus,
    val updatedAt: Long = 0L
)

@Serializable
data class KitsuUserResponse(
    val data: List<KitsuUserData>? = null
)

@Serializable
data class KitsuUserData(
    val id: String? = null,
    val attributes: KitsuUserAttributes? = null
)

@Serializable
data class KitsuUserAttributes(
    val name: String? = null,
    val slug: String? = null,
    val avatar: KitsuUserAvatar? = null
)

@Serializable
data class KitsuUserAvatar(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null
)

@Serializable
data class KitsuLibraryEntriesResponse(
    val data: List<KitsuLibraryEntryData> = emptyList(),
    val included: List<KitsuIncludedResource>? = null,
    val links: KitsuPaginationLinks? = null
)

@Serializable
data class KitsuLibraryEntryData(
    val id: String,
    val type: String = "library-entries",
    val attributes: KitsuLibraryEntryAttributes? = null,
    val relationships: KitsuLibraryEntryRelationships? = null
)

@Serializable
data class KitsuLibraryEntryAttributes(
    val status: String? = null,
    val progress: Int? = null,
    val rating: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("ratingTwenty") val ratingTwenty: Double? = null
)

@Serializable
data class KitsuLibraryEntryRelationships(
    val anime: KitsuRelationship? = null,
    val media: KitsuRelationship? = null
)

@Serializable
data class KitsuRelationship(
    val data: KitsuRelationshipData? = null
)

@Serializable
data class KitsuRelationshipData(
    val id: String,
    val type: String
)

@Serializable
data class KitsuIncludedResource(
    val id: String,
    val type: String,
    val attributes: KitsuIncludedAttributes? = null
)

@Serializable
data class KitsuIncludedAttributes(
    val slug: String? = null,
    val synopsis: String? = null,
    @SerialName("episodeCount") val episodeCount: Int? = null,
    @SerialName("posterImage") val posterImage: KitsuPosterImage? = null,
    val titles: KitsuTitles? = null,
    val status: String? = null
)

@Serializable
data class KitsuPosterImage(
    val tiny: String? = null,
    val small: String? = null,
    val medium: String? = null,
    val large: String? = null,
    val original: String? = null
)

@Serializable
data class KitsuTitles(
    val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
    val canonical: String? = null
)

@Serializable
data class KitsuPaginationLinks(
    val first: String? = null,
    val next: String? = null,
    val last: String? = null
)

@Serializable
data class KitsuCreateLibraryEntryRequest(
    val data: KitsuCreateLibraryEntryData
)

@Serializable
data class KitsuCreateLibraryEntryData(
    val type: String = "library-entries",
    val attributes: KitsuLibraryEntryAttributes,
    val relationships: KitsuCreateLibraryRelationships
)

@Serializable
data class KitsuCreateLibraryRelationships(
    val media: KitsuRelationship,
    val user: KitsuRelationship
)

@Serializable
data class KitsuPatchLibraryEntryRequest(
    val data: KitsuPatchLibraryEntryData
)

@Serializable
data class KitsuPatchLibraryEntryData(
    val id: String,
    val type: String = "library-entries",
    val attributes: KitsuLibraryEntryAttributes
)

@Serializable
data class KitsuErrorResponse(
    val errors: List<KitsuErrorItem>? = null
)

@Serializable
data class KitsuErrorItem(
    val code: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val status: String? = null
)
