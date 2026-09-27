package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R

internal sealed interface AwardClause {
    data class Won(val count: Int, val award: String) : AwardClause
    data class Nominated(val count: Int, val award: String) : AwardClause
    data class NominationsTotal(val count: Int) : AwardClause
    data class WinsAndNominations(val wins: Int, val nominations: Int) : AwardClause
    data class AnotherWinAndNominations(val wins: Int, val nominations: Int) : AwardClause
    data class Wins(val count: Int) : AwardClause
    data class Nominations(val count: Int) : AwardClause
    data object Winner : AwardClause
    data object NominatedBare : AwardClause
    data class Unknown(val text: String) : AwardClause
}

/** Translated templates injected so the formatter stays pure and JVM-testable. */
internal class AwardLabels(
    val won: (count: Int, award: String) -> String,
    val nominated: (count: Int, award: String) -> String,
    val nominationsTotal: (count: Int) -> String,
    val winsAndNominations: (wins: Int, nominations: Int) -> String,
    val anotherWinAndNominations: (wins: Int, nominations: Int) -> String,
    val wins: (count: Int) -> String,
    val nominations: (count: Int) -> String,
    val winner: () -> String,
    val nominatedBare: () -> String
)

private val AWARD_SEGMENT_SPLIT = Regex("""(?<=\.)\s+""")

private val WON_REGEX = Regex("""^won\s+(\d+)\s+(.+?)\.?$""", RegexOption.IGNORE_CASE)
private val NOMINATED_REGEX = Regex("""^nominated\s+for\s+(\d+)\s+(.+?)\.?$""", RegexOption.IGNORE_CASE)
private val NOMINATIONS_TOTAL_REGEX = Regex("""^(\d+)\s+nominations?\s+total\.?$""", RegexOption.IGNORE_CASE)
private val WINS_AND_NOMS_REGEX = Regex("""^(\d+)\s+wins?\s+&\s+(\d+)\s+nominations?\.?$""", RegexOption.IGNORE_CASE)
private val ANOTHER_WIN_AND_NOMS_REGEX =
    Regex("""^another\s+(?:(\d+)\s+)?wins?\s+&\s+(\d+)\s+nominations?\.?$""", RegexOption.IGNORE_CASE)
private val WINS_REGEX = Regex("""^(\d+)\s+wins?\.?$""", RegexOption.IGNORE_CASE)
private val NOMINATIONS_REGEX = Regex("""^(\d+)\s+nominations?\.?$""", RegexOption.IGNORE_CASE)
private val WINNER_REGEX = Regex("""^winner\.?$""", RegexOption.IGNORE_CASE)
private val NOMINATED_BARE_REGEX = Regex("""^nominated\.?$""", RegexOption.IGNORE_CASE)

internal fun parseAwardClause(segment: String): AwardClause {
    val text = segment.trim()
    WON_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.Won(m.groupValues[1].toInt(), m.groupValues[2].trim().removeSuffix("."))
    }
    NOMINATED_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.Nominated(m.groupValues[1].toInt(), m.groupValues[2].trim().removeSuffix("."))
    }
    NOMINATIONS_TOTAL_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.NominationsTotal(m.groupValues[1].toInt())
    }
    WINS_AND_NOMS_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.WinsAndNominations(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    ANOTHER_WIN_AND_NOMS_REGEX.matchEntire(text)?.let { m ->
        val wins = m.groupValues[1].toIntOrNull() ?: 1
        return AwardClause.AnotherWinAndNominations(wins, m.groupValues[2].toInt())
    }
    WINS_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.Wins(m.groupValues[1].toInt())
    }
    NOMINATIONS_REGEX.matchEntire(text)?.let { m ->
        return AwardClause.Nominations(m.groupValues[1].toInt())
    }
    if (WINNER_REGEX.matches(text)) return AwardClause.Winner
    if (NOMINATED_BARE_REGEX.matches(text)) return AwardClause.NominatedBare
    return AwardClause.Unknown(text)
}

/**
 * Localizes raw OMDb "Awards" text ("Won 1 Oscar. 4 nominations.") segment by
 * segment; unrecognized segments stay untouched in the source language.
 * Returns null when there is nothing to show ("N/A", blank).
 */
internal fun formatAwards(raw: String?, labels: AwardLabels): String? {
    if (raw.isNullOrBlank()) return null
    if (raw.trim().equals("N/A", ignoreCase = true)) return null
    val clauses = raw.split(AWARD_SEGMENT_SPLIT)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.trimEnd('.').equals("N/A", ignoreCase = true) }
    if (clauses.isEmpty()) return null
    return clauses
        .joinToString(". ") { clause ->
            when (val parsed = parseAwardClause(clause)) {
                is AwardClause.Won -> labels.won(parsed.count, parsed.award)
                is AwardClause.Nominated -> labels.nominated(parsed.count, parsed.award)
                is AwardClause.NominationsTotal -> labels.nominationsTotal(parsed.count)
                is AwardClause.WinsAndNominations ->
                    labels.winsAndNominations(parsed.wins, parsed.nominations)
                is AwardClause.AnotherWinAndNominations ->
                    labels.anotherWinAndNominations(parsed.wins, parsed.nominations)
                is AwardClause.Wins -> labels.wins(parsed.count)
                is AwardClause.Nominations -> labels.nominations(parsed.count)
                AwardClause.Winner -> labels.winner()
                AwardClause.NominatedBare -> labels.nominatedBare()
                is AwardClause.Unknown -> parsed.text
            }
        }
        .trimEnd('.') + "."
}

internal fun awardLabels(context: Context): AwardLabels {
    val res = context.resources
    fun won(count: Int, award: String) =
        res.getQuantityString(R.plurals.awards_won, count, count, award)
    fun nominated(count: Int, award: String) =
        res.getQuantityString(R.plurals.awards_nominated, count, count, award)
    return AwardLabels(
        won = ::won,
        nominated = ::nominated,
        nominationsTotal = { count ->
            res.getQuantityString(R.plurals.awards_nominations_total, count, count)
        },
        winsAndNominations = { wins, nominations ->
            res.getQuantityString(R.plurals.awards_wins_and_nominations, wins, wins, nominations)
        },
        anotherWinAndNominations = { wins, nominations ->
            res.getQuantityString(
                R.plurals.awards_another_win_and_nominations,
                wins,
                wins,
                nominations
            )
        },
        wins = { count ->
            res.getQuantityString(R.plurals.awards_wins, count, count)
        },
        nominations = { count ->
            res.getQuantityString(R.plurals.awards_nominations, count, count)
        },
        winner = { res.getString(R.string.awards_winner) },
        nominatedBare = { res.getString(R.string.awards_nominated_bare) }
    )
}

internal fun formatAwards(raw: String?, context: Context): String? =
    formatAwards(raw, awardLabels(context))
