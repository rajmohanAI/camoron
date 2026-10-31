package com.camoron.domain.model

/** The fixed sky for Sky view: bright stars, and the constellations' figures and names. */
class SkyCatalogue(val stars: List<Star>, val constellations: List<Constellation>) {

    /** [bv] is the star's colour index: below 0 blue-white, around 0.6 yellow like the sun, above 1.2 orange-red. */
    data class Star(val position: SkyPosition, val magnitude: Double, val bv: Double, val name: String?)

    /** [strokes] are the figure's lines, each a run of points to join. */
    data class Constellation(val abbreviation: String, val name: String, val label: SkyPosition, val strokes: List<List<SkyPosition>>)

    companion object {
        /**
         * Reads the catalogue's text form: `S ra dec magnitude b-v [name]` for each star,
         * `C abbreviation ra dec name` for each constellation and `L abbreviation ra dec ra dec ...`
         * for each stroke of its figure; `#` starts a comment.
         */
        fun parse(lines: Sequence<String>): SkyCatalogue {
            val stars = mutableListOf<Star>()
            val names = mutableListOf<Triple<String, String, SkyPosition>>()
            val strokes = mutableMapOf<String, MutableList<List<SkyPosition>>>()
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.trim().split(' ')
                when (parts[0]) {
                    "S" -> stars += Star(
                        SkyPosition(parts[1].toDouble(), parts[2].toDouble()),
                        parts[3].toDouble(),
                        parts[4].toDouble(),
                        parts.drop(5).joinToString(" ").ifEmpty { null },
                    )
                    "C" -> names += Triple(parts[1], parts.drop(4).joinToString(" "), SkyPosition(parts[2].toDouble(), parts[3].toDouble()))
                    "L" -> strokes.getOrPut(parts[1]) { mutableListOf() } +=
                        parts.drop(2).chunked(2).map { (ra, dec) -> SkyPosition(ra.toDouble(), dec.toDouble()) }
                }
            }
            // A constellation in two parts (Serpens) has a name for each; its figure goes with the first.
            val constellations = names.map { (abbreviation, name, label) ->
                Constellation(abbreviation, name, label, strokes.remove(abbreviation).orEmpty())
            }
            return SkyCatalogue(stars, constellations)
        }
    }
}
