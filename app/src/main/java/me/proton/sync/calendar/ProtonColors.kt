package me.proton.sync.calendar

/**
 * Proton's fixed accent-color palette for calendars and events.
 *
 * The Proton API accepts exactly these 20 hex values on the event `Color` field and
 * rejects anything else, so arbitrary per-event colors from third-party calendar apps
 * (Business Calendar, Google Calendar, ...) must be snapped to the nearest palette
 * entry before upload. Proton's own web importer does the same (`getClosestProtonColor`
 * in WebClients `packages/shared/lib/colors.ts`, which lists the palette as
 * `ACCENT_COLORS_MAP`); update this list when Proton changes its palette.
 *
 * Distance note: we use the plain Euclidean RGB distance. Proton's web importer computes
 * the green term differently, so our nearest match can differ from its choice on
 * green-heavy inputs.
 */
object ProtonColors {

    /** The 20 hex values in the order Proton lists them, each with its name. */
    val ACCENT_HEX: List<String> = listOf(
        "#8080FF", // purple
        "#DB60D6", // pink
        "#EC3E7C", // strawberry
        "#F78400", // carrot
        "#936D58", // sahara
        "#5252CC", // enzian
        "#A839A4", // plum
        "#BA1E55", // cerise
        "#C44800", // copper
        "#54473F", // soil
        "#415DF0", // slateblue
        "#179FD9", // pacific
        "#1DA583", // reef
        "#3CBB3A", // fern
        "#B4A40E", // olive
        "#273EB2", // cobalt
        "#0A77A6", // ocean
        "#0F735A", // pine
        "#258723", // forest
        "#807304", // pickle
    )

    private data class Rgb(val r: Int, val g: Int, val b: Int, val hex: String)

    private val palette: List<Rgb> = ACCENT_HEX.map { hex ->
        val v = hex.removePrefix("#").toInt(16)
        Rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF, hex)
    }

    /** Nearest accepted palette hex (uppercase, '#'-prefixed) for an Android ARGB color int. */
    fun nearestAccentHex(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return palette.minByOrNull { p ->
            (p.r - r) * (p.r - r) + (p.g - g) * (p.g - g) + (p.b - b) * (p.b - b)
        }!!.hex
    }
}
