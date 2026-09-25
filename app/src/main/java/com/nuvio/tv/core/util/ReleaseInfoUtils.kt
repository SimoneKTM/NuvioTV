package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import java.time.Clock
import java.time.LocalDate

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")

/**
 * Statuses that guarantee content has already been released (airing now, ended,
 * finished...). They win over date parsing: `released` on an ongoing series can
 * point at the next (future) episode, which must not hide a show that already
 * has watchable episodes.
 */
private val AIRED_STATUS = setOf(
    "returning series", "continuing", "currently airing", "airing", "airing now",
    "ongoing", "on air", "releasing", "current",
    "ended", "finished", "finished airing", "complete", "completed", "aired",
    "released", "hiatus", "on hiatus"
)

/** Explicit not-yet-released statuses — unreleased even when dates are missing or stale. */
private val NOT_YET_AIRED_STATUS = setOf(
    "planned", "in production", "post production", "upcoming", "unreleased",
    "not yet aired", "not yet released", "tba", "to be announced",
    "to be determined", "rumored"
)

fun MetaPreview.isUnreleased(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): Boolean {
    status?.trim()?.replace('_', ' ')?.lowercase()?.let { normalizedStatus ->
        if (normalizedStatus in AIRED_STATUS) return false
        if (normalizedStatus in NOT_YET_AIRED_STATUS) return true
    }

    released?.trim()?.takeIf { it.isNotEmpty() }?.let { rawReleased ->
        isEpisodeReleaseAired(rawReleased, clock)?.let { hasAired ->
            return !hasAired
        }
    }

    val info = releaseInfo ?: return false
    isEpisodeReleaseAired(info.trim(), clock)?.let { hasAired ->
        return !hasAired
    }
    val yearStr = YEAR_REGEX.find(info)?.value ?: return false
    val year = yearStr.toIntOrNull() ?: return false
    return year > today.year
}

fun CatalogRow.filterReleasedItems(
    today: LocalDate,
    clock: Clock = Clock.systemDefaultZone()
): CatalogRow {
    val filtered = items.filterNot { it.isUnreleased(today, clock) }
    return if (filtered.size == items.size) this else copy(items = filtered)
}

/**
 * True when the item carries no release information at all. Only meaningful for
 * sources where dates are authoritative (e.g. TMDB, where a movie without a
 * release_date is unannounced); addon metadata often legitimately omits dates.
 */
fun MetaPreview.hasNoReleaseInfo(): Boolean =
    released.isNullOrBlank() && releaseInfo.isNullOrBlank()
