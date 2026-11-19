package dev.sluide.ide.clipboard

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Converting's data-format detection as a [TextTypeSource] (ADR 0187): the scores of the [families] mapped to the text
 * types of their formats' first extensions (unknown ones dropped), taken as they are. A family that throws counts as
 * no match.
 */
class ConvertingTextTypes(
    private val families: List<FormatFamily<*>>,
) : TextTypeSource {
    override fun scores(text: String): List<TypeScore> = families.flatMap { matchesOf(it, text) }.mapNotNull(::scoreOf)

    private fun matchesOf(
        family: FormatFamily<*>,
        text: String,
    ): List<FormatMatch> =
        try {
            family.detect(text)
        } catch (_: Exception) {
            emptyList()
        }

    private fun scoreOf(match: FormatMatch): TypeScore? =
        match.format.extensions
            .firstOrNull()
            ?.let(TextTypes::byExtension)
            ?.let { TypeScore(it, match.score) }
}
