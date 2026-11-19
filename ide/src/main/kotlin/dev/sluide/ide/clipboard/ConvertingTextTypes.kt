package dev.sluide.ide.clipboard

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Converting's data-format detection as a [TextTypeSource] (ADR 0187): the scores of the [families] mapped to the text
 * types of their formats' first extensions (unknown ones dropped), held to the [DataTextGuards] by the format's id, the
 * score taken as it is. A family that throws counts as no match.
 */
class ConvertingTextTypes(
    private val families: List<FormatFamily<*>>,
) : TextTypeSource {
    override fun scores(text: String): List<TypeScore> =
        families.flatMap { matchesOf(it, text) }.mapNotNull { scoreOf(it, text) }

    private fun matchesOf(
        family: FormatFamily<*>,
        text: String,
    ): List<FormatMatch> =
        try {
            family.detect(text)
        } catch (_: Exception) {
            emptyList()
        }

    private fun scoreOf(
        match: FormatMatch,
        text: String,
    ): TypeScore? {
        val extension = match.format.extensions.firstOrNull() ?: return null
        val type = TextTypes.byExtension(extension) ?: return null
        return if (DataTextGuards.accepts(match.format.id, text)) TypeScore(type, match.score) else null
    }
}
