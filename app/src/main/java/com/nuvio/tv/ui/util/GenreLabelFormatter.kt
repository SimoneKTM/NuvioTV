package com.nuvio.tv.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.R

@Composable
fun localizedGenreLabel(genre: String): String {
    val context = LocalContext.current
    return localizedGenreLabel(context, genre)
}

/**
 * Addon and Trakt catalogs hand over English genre labels in arbitrary
 * shapes ("Documentaries", "Reality TV"); canonicalize the common variants
 * before the exact-match lookup so they still resolve to a localized label.
 */
private val GENRE_LABEL_ALIASES = mapOf(
    "documentaries" to "documentary",
    "documentary series" to "documentary",
    "documentaries series" to "documentary",
    "docuseries" to "documentary",
    "docudrama" to "documentary",
    "non fiction" to "documentary",
    "reality tv" to "reality",
    "reality show" to "reality",
    "reality shows" to "reality",
    "reality-tv" to "reality",
    "science-fiction" to "science fiction",
    "scifi" to "science fiction",
    "sci fi" to "science fiction",
    "sci-fi" to "science fiction",
    "sci fi & fantasy" to "sci-fi & fantasy",
    "kids tv" to "kids",
    "children" to "kids"
)
private fun canonicalGenreKey(genre: String): String {
    val key = genre.lowercase().trim()
    return GENRE_LABEL_ALIASES[key] ?: key
}

fun localizedGenreLabel(context: Context, genre: String): String = when (canonicalGenreKey(genre)) {
    "action" -> context.getString(R.string.genre_action)
    "adventure" -> context.getString(R.string.genre_adventure)
    "animation" -> context.getString(R.string.genre_animation)
    "comedy" -> context.getString(R.string.genre_comedy)
    "crime" -> context.getString(R.string.genre_crime)
    "documentary" -> context.getString(R.string.genre_documentary)
    "drama" -> context.getString(R.string.genre_drama)
    "family" -> context.getString(R.string.genre_family)
    "fantasy" -> context.getString(R.string.genre_fantasy)
    "history" -> context.getString(R.string.genre_history)
    "horror" -> context.getString(R.string.genre_horror)
    "music" -> context.getString(R.string.genre_music)
    "mystery" -> context.getString(R.string.genre_mystery)
    "romance" -> context.getString(R.string.genre_romance)
    "science fiction" -> context.getString(R.string.genre_science_fiction)
    "tv movie" -> context.getString(R.string.genre_tv_movie)
    "thriller" -> context.getString(R.string.genre_thriller)
    "war" -> context.getString(R.string.genre_war)
    "western" -> context.getString(R.string.genre_western)
    "action & adventure" -> context.getString(R.string.genre_action_adventure)
    "kids" -> context.getString(R.string.genre_kids)
    "news" -> context.getString(R.string.genre_news)
    "reality" -> context.getString(R.string.genre_reality)
    "sci-fi & fantasy" -> context.getString(R.string.genre_sci_fi_fantasy)
    "soap" -> context.getString(R.string.genre_soap)
    "talk" -> context.getString(R.string.genre_talk)
    "war & politics" -> context.getString(R.string.genre_war_politics)
    else -> genre
}
