package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Markdown by how many kinds of its syntax appear: ATX headings, fenced blocks, list items, links, tables, bold text,
 * block quotes, setext headings, front matter. One kind is not enough (a `#` comment looks like a heading); a fenced
 * block with another kind scores as high as a code heuristic can, so a README with code stays Markdown on the tie.
 */
internal object MarkdownRule : TextTypeSource {
    const val TWO_KINDS = 60
    const val THREE_KINDS = 80
    const val FOUR_KINDS = 90

    private val fence = Regex("""^[ \t]{0,3}(?:```|~~~)""", RegexOption.MULTILINE)

    private val kinds =
        listOf(
            Signal("""^#{1,6}[ \t]++\S""", 1),
            Signal("""^[ \t]{0,3}(?:[-*+]|\d{1,9}[.)])[ \t]++\S""", 1),
            Signal("""(?<![\w\])])!?\[[^\[\]\n]++]\([^()\s]++\)""", 1),
            Signal("""^[ \t]*+\|?[ \t]*+:?-{3,}+:?[ \t]*+\|[ \t]*+:?-{3,}""", 1),
            Signal("""(?<![\w*])\*\*[^*\s][^*\n]*+\*\*(?![\w*])""", 1),
            Signal("""^>[ \t]?\S""", 1),
            Signal("""^\S[^\n]*+\n={3,}+[ \t]*+$""", 1),
            // Front matter: `---` and a `key: value` line.
            Signal("""\A---[ \t]*+\r?\n[^\n:]*+:[^\n]*+\n""", 1),
        )

    override fun scores(text: String): List<TypeScore> {
        val fenced = fence.findAll(text).take(2).count() == 2
        val found = weigh(text, kinds) + if (fenced) 1 else 0
        val score =
            when {
                found >= 4 || (fenced && found >= 2) -> FOUR_KINDS
                found == 3 -> THREE_KINDS
                found == 2 -> TWO_KINDS
                else -> return emptyList()
            }
        return listOf(TypeScore(TextTypes.MARKDOWN, score))
    }
}
