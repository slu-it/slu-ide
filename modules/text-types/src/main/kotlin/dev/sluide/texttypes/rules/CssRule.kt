package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/** CSS by keyword heuristics: rule blocks with `property: value;` declarations, at-rules, custom properties. */
internal object CssRule : TextTypeSource {
    private val signals =
        listOf(
            // A selector line ending in `{` (not a declaration of code: no `=`, `(`, `;` and no code keyword) with a
            // declaration on the next line.
            Signal(
                """^[ \t]*+(?!(?:export|declare|interface|class|type|enum|public|private|protected|internal""" +
                    """|data|object|fun|function|if|for|while|switch|else|try|catch|when)\b)""" +
                    """[^\s{};=()][^{};=\n]*+\{[ \t]*+\r?\n""" +
                    """[ \t]*+-{0,2}[a-zA-Z][\w-]*+[ \t]*+:[^;{}\n]*+;""",
                40,
            ),
            // A rule on one line: `a { color: red; }`.
            Signal("""\{[ \t]*+-{0,2}[a-zA-Z][\w-]*+[ \t]*+:[^;{}\n]++;[^{}\n]*+}""", 30),
            Signal("""^[ \t]*+@(?:media|import|font-face|keyframes|supports|charset|layer|container|page)\b""", 35),
            Signal("""^[ \t]*+--[\w-]++[ \t]*+:""", 25),
            Signal("""^[ \t]*+-(?:fx|webkit|moz|ms)-[\w-]++[ \t]*+:""", 25),
            Signal(
                """^[ \t]*+(?:color|background(?:-color)?|margin|padding|display|font-(?:size|family|weight)|width""" +
                    """|height|border(?:-radius)?|position|flex|gap|opacity|z-index|line-height|text-align|cursor)""" +
                    """[ \t]*+:[^;{}\n]++;""",
                20,
            ),
            Signal("""!important\b""", 15),
        )

    override fun scores(text: String): List<TypeScore> = heuristic(TextTypes.CSS, weigh(text, signals))
}
