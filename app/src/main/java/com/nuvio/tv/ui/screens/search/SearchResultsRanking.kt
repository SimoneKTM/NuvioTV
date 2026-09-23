package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.domain.model.MetaPreview
import java.util.Locale

private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
private val SEASON_SUFFIX_REGEX = Regex(
    """\s*[-–:,(]*\s*(?:season|stagione|series|volume|vol|part(?:e)?)\s*[-]?\s*\d{1,3}\s*\)?$""",
    RegexOption.IGNORE_CASE
)
private val YEAR_SUFFIX_REGEX = Regex("""\s*[(\[【]?\s*(?:19|20)\d{2}\s*[)\]】]?\s*$""")
private val NON_ALNUM_REGEX = Regex("""[^0-9a-zà-öø-ÿ]+""")

/** 0 = exact title match, lower is closer to the typed query. */
internal fun searchMatchQuality(query: String, title: String): Int {
    val q = searchNormalizeText(query)
    val t = searchNormalizeText(title)
    if (q.isEmpty() || t.isEmpty()) return Int.MAX_VALUE
    return when {
        t == q -> 0
        t.startsWith(q) -> 1
        t.split(' ').any { it.startsWith(q) } -> 2
        t.contains(q) -> 3
        q.split(' ').all { token -> token.isNotEmpty() && t.contains(token) } -> 4
        else -> 5
    }
}

/**
 * Franchise root: shorter titles that are a strict prefix of longer ones
 * (e.g. "dexter" owns "dexter new blood") share one family so sequels can sit
 * in release order under the original.
 */
internal fun searchTitleFamily(name: String, allNormalized: List<String>): String {
    val self = searchNormalizeTitle(name)
    if (self.isEmpty()) return self
    var family = self
    for (candidate in allNormalized) {
        if (candidate.length < family.length &&
            family.startsWith(candidate) &&
            (family.length == candidate.length || !family[candidate.length].isLetter())
        ) {
            family = candidate
        }
    }
    return family
}

internal fun searchReleaseYear(item: MetaPreview): Int? {
    listOfNotNull(item.released, item.releaseInfo).forEach { raw ->
        YEAR_REGEX.find(raw)?.value?.toIntOrNull()?.let { return it }
    }
    return null
}

internal fun searchNormalizeTitle(name: String): String {
    var base = searchNormalizeText(name)
    while (true) {
        val noSeason = base.replace(SEASON_SUFFIX_REGEX, "").trim()
        val noYear = noSeason.replace(YEAR_SUFFIX_REGEX, "").trim()
        if (noYear == base) break
        base = noYear
    }
    return base.trim()
}

internal fun searchNormalizeText(value: String): String =
    NON_ALNUM_REGEX
        .replace(value.lowercase(Locale.ROOT), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

/**
 * Relevance first (closer title match), then more popular franchises, and
 * within one franchise oldest release first (Dexter before Dexter: New Blood).
 */
internal fun searchResultsComparator(query: String): Comparator<MetaPreview> {
    val normalizedTitles = listOf(searchNormalizeTitle(query))

    // Families are assigned against the full result set once via decorate-sort-undecorate
    // in [rankSearchResults]; this comparator only needs per-item keys that stay consistent
    // when familyRank is empty (single-item sorts / tests).
    return Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { searchMatchQuality(query, it.name) },
            { searchFamilyPopularityRank(it) },
            { searchTitleFamily(it.name, normalizedTitles) },
            { searchReleaseYear(it) ?: Int.MAX_VALUE },
            { it.imdbRating ?: -1f },
            { it.name.lowercase(Locale.ROOT) }
        ).let { base ->
            if (base != 0) base else left.id.compareTo(right.id)
        }
    }
}

private var familyRankByTitle: Map<String, Int> = emptyMap()

private fun searchFamilyPopularityRank(item: MetaPreview): Int =
    familyRankByTitle[searchNormalizeTitle(item.name)] ?: 0

/**
 * Sorts a merged search grid: best title matches first, popular franchises
 * ahead of deep cuts, and sequels after their original in release order.
 */
internal fun rankSearchResults(query: String, items: List<MetaPreview>): List<MetaPreview> {
    if (items.size <= 1) return items

    val normalizedTitles = items
        .map { searchNormalizeTitle(it.name) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedBy { it.length }

    val families = LinkedHashMap<String, String>()
    normalizedTitles.forEach { normalized ->
        families[normalized] = searchTitleFamily(
            name = normalized,
            allNormalized = normalizedTitles
        )
    }

    val bestRatingByFamily = HashMap<String, Float>()
    items.forEach { item ->
        val family = families[searchNormalizeTitle(item.name)] ?: searchNormalizeTitle(item.name)
        val rating = item.imdbRating ?: -1f
        if (rating > (bestRatingByFamily[family] ?: Float.NEGATIVE_INFINITY)) {
            bestRatingByFamily[family] = rating
        }
    }

    val familyRank = bestRatingByFamily.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, Float>> { it.value }
                .thenBy { it.key }
        )
        .mapIndexed { index, entry -> entry.key to index }
        .toMap()

    synchronized(familyRankByTitle) {
        familyRankByTitle = families.entries
            .map { (normalized, family) -> normalized to (familyRank[family] ?: Int.MAX_VALUE) }
            .toMap()
    }

    return try {
        items.sortedWith(searchResultsComparator(query))
    } finally {
        synchronized(familyRankByTitle) {
            familyRankByTitle = emptyMap()
        }
    }
}
