package com.nuvio.tv.domain.model

const val METADATA_LANGUAGE_SYSTEM = "system"

fun systemMetadataLanguage(): String =
    java.util.Locale.getDefault().language
        .takeIf { it.isNotBlank() }
        ?: "en"

fun resolveMetadataLanguage(preference: String?): String {
    val raw = preference?.trim()
    if (raw.isNullOrEmpty() || raw.equals(METADATA_LANGUAGE_SYSTEM, ignoreCase = true)) {
        return systemMetadataLanguage()
    }
    return raw.replace('_', '-')
}
