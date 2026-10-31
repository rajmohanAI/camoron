package com.camoron.domain

import com.camoron.domain.model.SkyCatalogue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SkyCatalogueTest {

    @Test
    fun `reads stars, names and figures`() {
        val catalogue = SkyCatalogue.parse(
            """
            # comment
            S 101.2872 -16.7161 -1.44 0.01 Sirius
            S 279.2347 38.7837 0.03 -0.00 Vega
            S 10.0 20.0 4.50 1.10
            C Ori 83.000 1.000 Orion
            L Ori 88.793 7.407 81.283 6.350
            L Ori 83.858 -5.910 85.190 -1.943 86.939 -9.670
            C Lyr 283.000 37.000 Lyra
            """.trimIndent().lineSequence(),
        )
        assertThat(catalogue.stars.map { it.name }).containsExactly("Sirius", "Vega", null).inOrder()
        assertThat(catalogue.stars.first().magnitude).isEqualTo(-1.44)
        val orion = catalogue.constellations.first()
        assertThat(orion.name).isEqualTo("Orion")
        assertThat(orion.strokes.map { it.size }).containsExactly(2, 3).inOrder()
        assertThat(catalogue.constellations.last().strokes).isEmpty()
    }

    @Test
    fun `the bundled catalogue reads in full`() {
        // Tests run from the domain module's folder.
        val file = java.io.File("../data/processing/src/main/assets/sky_catalogue.txt")
        val catalogue = file.useLines { SkyCatalogue.parse(it) }
        assertThat(catalogue.stars.size).isGreaterThan(2_500)
        assertThat(catalogue.constellations).hasSize(89)
        assertThat(catalogue.stars.first().name).isEqualTo("Sirius")
        assertThat(catalogue.constellations.count { it.strokes.isNotEmpty() }).isEqualTo(88)
        assertThat(catalogue.constellations.filter { it.abbreviation == "Ser" }.map { it.name })
            .containsExactly("Serpens Caput", "Serpens Cauda")
    }
}
