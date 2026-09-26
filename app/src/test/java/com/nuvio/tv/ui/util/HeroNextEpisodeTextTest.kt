package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroNextEpisodeTextTest {

    private val relativeLabels = mapOf(
        "Tra 2 giorni" to 2,
        "Tra 6 giorni" to 6,
        "Tra 7 giorni" to 7
    )

    private fun resolve(cardLabel: String): String {
        return resolveHeroNextEpisodeText(
            cardLabel = cardLabel,
            todayLabel = "Oggi",
            tomorrowLabel = "Domani",
            relativeLabels = relativeLabels,
            todayText = "Nuovo Episodio Oggi",
            tomorrowText = "Nuovo Episodio Domani",
            inDaysText = { days -> "Nuovo Episodio tra $days giorni" },
            onDateText = { date -> "Nuovo Episodio in uscita il $date" }
        )
    }

    @Test
    fun `today card label becomes hero today text`() {
        assertEquals("Nuovo Episodio Oggi", resolve("Oggi"))
    }

    @Test
    fun `tomorrow card label becomes hero tomorrow text`() {
        assertEquals("Nuovo Episodio Domani", resolve("Domani"))
    }

    @Test
    fun `relative card label keeps the day count in the hero text`() {
        assertEquals("Nuovo Episodio tra 6 giorni", resolve("Tra 6 giorni"))
    }

    @Test
    fun `absolute date card label gets the release wording`() {
        assertEquals("Nuovo Episodio in uscita il 21 ottobre", resolve("21 ottobre"))
    }

    @Test
    fun `unknown label falls back to the date wording`() {
        assertEquals("Nuovo Episodio in uscita il 3 novembre", resolve("3 novembre"))
    }
}
