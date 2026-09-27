package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AwardsFormatterTest {

    private val labels = AwardLabels(
        won = { n, award -> if (n == 1) "Vinto $n $award" else "Vinti $n $award" },
        nominated = { n, award -> if (n == 1) "Candidatura: $n $award" else "Candidature: $n $award" },
        nominationsTotal = { n -> if (n == 1) "$n candidatura totale" else "$n candidature totali" },
        winsAndNominations = { w, n -> "Vittorie: $w. Candidature: $n" },
        anotherWinAndNominations = { w, n -> if (w == 1) "Altra vittoria. Candidature: $n" else "$w vittorie aggiuntive. Candidature: $n" },
        wins = { n -> if (n == 1) "$n vittoria" else "$n vittorie" },
        nominations = { n -> if (n == 1) "$n candidatura" else "$n candidature" },
        winner = { "Vincitore" },
        nominatedBare = { "Nominato" }
    )

    @Test
    fun `won oscar with nominations translates to italian`() {
        val out = formatAwards("Won 1 Oscar. 4 nominations.", labels)
        assertEquals("Vinto 1 Oscar. 4 candidature.", out)
    }

    @Test
    fun `nominated for golden globes with extra win translates`() {
        val out = formatAwards("Nominated for 2 Golden Globes. Another win & 5 nominations.", labels)
        assertEquals("Candidature: 2 Golden Globes. Altra vittoria. Candidature: 5.", out)
    }

    @Test
    fun `emmy wins with nominations total translate`() {
        val out = formatAwards("Won 13 Primetime Emmys. 29 nominations total.", labels)
        assertEquals("Vinti 13 Primetime Emmys. 29 candidature totali.", out)
    }

    @Test
    fun `wins and nominations pair translates`() {
        val out = formatAwards("Nominated for 1 Oscar. 2 wins & 5 nominations.", labels)
        assertEquals("Candidatura: 1 Oscar. Vittorie: 2. Candidature: 5.", out)
    }

    @Test
    fun `single win line translates`() {
        assertEquals("1 vittoria.", formatAwards("1 win.", labels))
    }

    @Test
    fun `single nomination line translates`() {
        assertEquals("1 candidatura.", formatAwards("1 nomination.", labels))
    }

    @Test
    fun `na and blank produce nothing`() {
        assertNull(formatAwards("N/A", labels))
        assertNull(formatAwards("N/A.", labels))
        assertNull(formatAwards("  ", labels))
        assertNull(formatAwards(null, labels))
    }

    @Test
    fun `unknown segment keeps source language`() {
        val out = formatAwards("Won 1 Saturn Award. Some festival mention.", labels)
        assertEquals("Vinto 1 Saturn Award. Some festival mention.", out)
    }

    @Test
    fun `another with win count translates`() {
        val out = formatAwards("Won 1 Oscar. Another 64 wins & 62 nominations.", labels)
        assertEquals("Vinto 1 Oscar. 64 vittorie aggiuntive. Candidature: 62.", out)
    }

    @Test
    fun `bare winner segment translates`() {
        assertEquals("Vincitore.", formatAwards("Winner.", labels))
    }

    @Test
    fun `bare nominated segment translates`() {
        val out = formatAwards("Nominated. 5 nominations.", labels)
        assertEquals("Nominato. 5 candidature.", out)
    }
}
