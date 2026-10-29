package dev.sluide.coloring.ui.vision

import dev.sluide.coloring.analysis.Deficiency
import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.analysis.flatten
import dev.sluide.coloring.analysis.simulate
import dev.sluide.coloring.analysis.wcagVerdicts
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.contrast.ratioText

/**
 * One row of the Vision grid: how the colors look with [deficiency] (`null`: normal vision). [text] and [background]
 * are the simulated contrast pair; [ratioText] is its WCAG ratio and [passes] whether it reaches AA for normal text.
 */
internal data class VisionRow(
    val deficiency: Deficiency?,
    val label: String,
    val description: String,
    val text: CssColor,
    val background: CssColor,
    val ratioText: String,
    val passes: Boolean,
)

private val ROWS: List<Triple<Deficiency?, String, String>> =
    listOf(
        Triple(null, "Normal vision", "Typical color vision."),
        Triple(Deficiency.PROTANOPIA, "Protanopia", "No red cones, about 1 % of men."),
        Triple(Deficiency.DEUTERANOPIA, "Deuteranopia", "No green cones, about 1 % of men."),
        Triple(Deficiency.TRITANOPIA, "Tritanopia", "No blue cones, rare."),
        Triple(Deficiency.ACHROMATOPSIA, "Achromatopsia", "No color vision, very rare."),
    )

/**
 * The five rows for the pair [text] on [background]. The pair is composited first (background over white, text over
 * that background), as a viewer sees the pixels, then simulated; [VisionRow.text] and [VisionRow.background] are
 * therefore opaque.
 */
internal fun visionRows(
    text: CssColor,
    background: CssColor,
): List<VisionRow> {
    val flatBackground = flatten(background, WHITE)
    val flatText = flatten(text, flatBackground)
    return ROWS.map { (deficiency, label, description) ->
        fun see(color: CssColor) = if (deficiency == null) color else simulate(color, deficiency)
        val seenText = see(flatText)
        val seenBackground = see(flatBackground)
        val ratio = contrastRatio(seenText, seenBackground)
        VisionRow(
            deficiency,
            label,
            description,
            seenText,
            seenBackground,
            ratioText(ratio),
            wcagVerdicts(ratio).aaNormal,
        )
    }
}

private val WHITE = CssColor.srgb(1.0, 1.0, 1.0)

/** A warning when the pair passes AA with normal vision but fails under a deficiency; `null` otherwise. */
internal fun warning(rows: List<VisionRow>): String? {
    if (rows.firstOrNull { it.deficiency == null }?.passes != true) return null
    val failing = rows.filter { it.deficiency != null && !it.passes }.map { it.label }
    if (failing.isEmpty()) return null
    val names = failing.map { it.lowercase() }
    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "The pair loses contrast for people with $list; increase the lightness difference."
}
