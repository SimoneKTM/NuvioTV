package com.nuvio.tv.data.translation

internal const val TRANSLATION_MIN_TEXT_LENGTH = 40
internal const val TRANSLATION_MAX_TEXT_LENGTH = 2_000

/**
 * Short-form metadata such as OMDb awards ("Won 1 Oscar. 4 nominations.") is
 * far below the synopsis minimum but still worth translating on-device.
 */
internal const val TRANSLATION_SHORT_MIN_TEXT_LENGTH = 4

internal val TRANSLATION_SUPPORTED_LANGUAGES: Set<String> = setOf(
    "am", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "cy", "da", "de", "el", "en", "es",
    "et", "eu", "fa", "fi", "fil", "fr", "ga", "gl", "gu", "he", "hi", "hr", "hu", "hy", "id",
    "is", "it", "ja", "ka", "kk", "km", "kn", "ko", "ky", "lo", "lt", "lv", "mk", "ml", "mn",
    "mr", "ms", "my", "ne", "nl", "no", "pa", "pl", "pt", "ro", "ru", "si", "sk", "sl", "sq",
    "sr", "sv", "sw", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zh"
)

internal fun normalizeLanguageCode(code: String?): String? {
    val trimmed = code?.trim()?.lowercase() ?: return null
    if (trimmed.isEmpty() || trimmed == "und") return null
    val base = trimmed.substringBefore('-').substringBefore('_')
    if (base.isEmpty()) return null
    return when (base) {
        "iw", "ji" -> "he"
        "in" -> "id"
        "tl" -> "fil"
        else -> base
    }
}

internal fun shouldTranslate(
    text: String?,
    targetLanguage: String?,
    minLength: Int = TRANSLATION_MIN_TEXT_LENGTH
): Boolean {
    val target = normalizeLanguageCode(targetLanguage) ?: return false
    if (target !in TRANSLATION_SUPPORTED_LANGUAGES) return false
    val body = text?.trim() ?: return false
    return body.length >= minLength && body.length <= TRANSLATION_MAX_TEXT_LENGTH
}

internal fun shouldTranslateDetected(
    text: String?,
    detectedLanguage: String?,
    targetLanguage: String?,
    minLength: Int = TRANSLATION_MIN_TEXT_LENGTH
): Boolean {
    if (!shouldTranslate(text, targetLanguage, minLength)) return false
    val detected = normalizeLanguageCode(detectedLanguage) ?: return false
    return detected != normalizeLanguageCode(targetLanguage)
}

internal fun translationCacheKey(targetLanguage: String, text: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest("$targetLanguage|$text".toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
