package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.util.isEpisodeReleaseAired
import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

internal fun parseEpisodeReleaseDate(raw: String?): LocalDate? {
    return parseEpisodeReleaseLocalDate(raw)
}

internal fun computeAirDateBadgeText(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
    if (isEpisodeReleaseAired(releasedIso) == true) return null

    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0 -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow)
        daysUntil in 2..7 -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date, it) }
    }
}

/**
 * Short version of [computeAirDateBadgeText] for compact card styles (wide/poster).
 * Returns "Today", "Tomorrow", "In X Days" instead of "Airs Today", etc.
 */
internal fun computeAirDateBadgeTextShort(
    context: Context,
    releasedIso: String?,
    airDateLabel: String?
): String? {
    if (releasedIso.isNullOrBlank()) {
        return airDateLabel?.let { context.getString(R.string.cw_airs_date_short, it) }
    }
    if (isEpisodeReleaseAired(releasedIso) == true) return null

    val releaseDate = parseEpisodeReleaseDate(releasedIso) ?: return null
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0L -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today_short)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow_short)
        daysUntil in 2L..7L -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days_short,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> airDateLabel?.let { context.getString(R.string.cw_airs_date_short, it) }
    }
}

internal fun computeUpcomingReleaseBadgeText(
    context: Context,
    releaseDate: LocalDate
): String? {
    val today = LocalDate.now(ZoneId.systemDefault())
    val daysUntil = ChronoUnit.DAYS.between(today, releaseDate)

    return when {
        daysUntil < 0L -> null
        daysUntil == 0L -> context.getString(R.string.cw_airs_today_short)
        daysUntil == 1L -> context.getString(R.string.cw_airs_tomorrow_short)
        daysUntil in 2L..7L -> context.resources.getQuantityString(
            R.plurals.cw_airs_in_days_short,
            daysUntil.toInt(),
            daysUntil.toInt()
        )
        else -> formatUpcomingReleaseDate(releaseDate, today)
    }
}

private fun formatUpcomingReleaseDate(releaseDate: LocalDate, today: LocalDate): String {
    val locale = Locale.getDefault()
    val skeleton = if (releaseDate.year == today.year) "dMMMM" else "dMMMMy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    val date = Date(releaseDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    return SimpleDateFormat(pattern, locale).format(date)
}

/**
 * Formats any release date as a standalone label (e.g. "9 Ottobre") for
 * past and future dates alike — used by the "Latest Releases" row cards.
 */
internal fun formatCalendarReleaseDate(releaseDate: LocalDate): String {
    return formatUpcomingReleaseDate(releaseDate, LocalDate.now(ZoneId.systemDefault()))
}

/**
 * Turns the short card label produced by [computeUpcomingReleaseBadgeText]
 * ("Today"/"Oggi", "Tomorrow"/"Domani", "In 6 Days"/"Tra 6 giorni", "21 ottobre")
 * into the hero line ("New Episode Today" / "Nuovo Episodio Oggi", ...).
 * Pure so it can be unit tested; resource resolution happens in
 * [computeHeroNextEpisodeText].
 */
internal fun resolveHeroNextEpisodeText(
    cardLabel: String,
    todayLabel: String,
    tomorrowLabel: String,
    relativeLabels: Map<String, Int>,
    todayText: String,
    tomorrowText: String,
    inDaysText: (Int) -> String,
    onDateText: (String) -> String
): String {
    return when {
        cardLabel == todayLabel -> todayText
        cardLabel == tomorrowLabel -> tomorrowText
        else -> relativeLabels[cardLabel]?.let(inDaysText) ?: onDateText(cardLabel)
    }
}

internal fun computeHeroNextEpisodeText(context: Context, cardLabel: String): String {
    val resources = context.resources
    val relativeLabels = (2..7).associate { days ->
        resources.getQuantityString(R.plurals.cw_airs_in_days_short, days, days) to days
    }
    return resolveHeroNextEpisodeText(
        cardLabel = cardLabel,
        todayLabel = context.getString(R.string.cw_airs_today_short),
        tomorrowLabel = context.getString(R.string.cw_airs_tomorrow_short),
        relativeLabels = relativeLabels,
        todayText = context.getString(R.string.hero_next_episode_today),
        tomorrowText = context.getString(R.string.hero_next_episode_tomorrow),
        inDaysText = { days ->
            resources.getQuantityString(R.plurals.hero_next_episode_in_days, days, days)
        },
        onDateText = { date ->
            context.getString(R.string.hero_next_episode_on_date, date)
        }
    )
}
