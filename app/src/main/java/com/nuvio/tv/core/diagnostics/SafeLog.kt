package com.nuvio.tv.core.diagnostics

import android.util.Log

/**
 * Helper di logging che impedisce ai token (JWT, Bearer, api key) di finire in logcat.
 *
 * Su Fire TV il logcat è leggibile da chiunque abbia ADB: mai stampare credenziali.
 * Le eccezioni Supabase (PostgrestRestException) incorporano l'intestazione della richiesta
 * nel proprio messaggio, perció [logError] ridacchia anche lo stack trace renderizzato.
 */

private val SENSITIVE_LOG_PATTERNS = listOf(
    // Authorization=[Bearer eyJ...] / Authorization: Bearer eyJ...
    Regex("""(?i)(authorization\s*[=:]\s*\[?(?:bearer\s+)?)[^\s\],]+"""),
    // apikey=[eyJ...] / api_key=... / access_token=...
    Regex("""(?i)((?:apikey|api_key|access_token|refresh_token|token|password|secret)\s*[=:]\s*\[?)[^\s\],]+"""),
    // JWT nudo (header.payload.signature)
    Regex("""\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b"""),
    // ?token=... / &apikey=... in una URL
    Regex("""(?i)([?&](?:token|apikey|api_key|key|auth)=)[^&\s]+"""),
)

fun redactSensitiveLogText(text: String): String {
    if (text.isEmpty()) return text
    var out = text
    out = SENSITIVE_LOG_PATTERNS[0].replace(out) { it.groupValues[1] + "***" }
    out = SENSITIVE_LOG_PATTERNS[1].replace(out) { it.groupValues[1] + "***" }
    out = SENSITIVE_LOG_PATTERNS[2].replace(out, "***")
    out = SENSITIVE_LOG_PATTERNS[3].replace(out) { it.groupValues[1] + "***" }
    return out
}

fun logError(tag: String, message: String) {
    Log.e(tag, redactSensitiveLogText(message))
}

fun logError(tag: String, message: String, error: Throwable?) {
    Log.e(tag, renderRedacted(message, error))
}

fun logWarn(tag: String, message: String, error: Throwable?) {
    Log.w(tag, renderRedacted(message, error))
}

private fun renderRedacted(message: String, error: Throwable?): String {
    if (error == null) return redactSensitiveLogText(message)
    // Lo Throwable non viene passato a Log.e: la sua toString() contiene l'header
    // della richiesta con il Bearer token. Lo renderizziamo e ridacchiamo noi.
    return redactSensitiveLogText(message + "\n" + error.stackTraceToString())
}
