package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Kotlin and Java by keyword heuristics, each scored by the independent signs found. The signs that both could show
 * are split by what only one has: `package`/`import` without `;` are Kotlin, with `;` Java; `fun`, `val` with a type
 * and `data class` are Kotlin, typed declarations ending in `;` Java.
 */
internal object JvmRules : TextTypeSource {
    private val kotlin =
        listOf(
            // `fun name(` without a blank before the parenthesis, as Kotlin is written (prose "fun fact (really)").
            Signal(
                """^[ \t]*+(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec""" +
                    """|open|abstract)[ \t]++){0,4}fun[ \t]++(?:<[^>\n]*+>[ \t]*+)?[\w.`]++\(""",
                50,
            ),
            // `val name: Type`, a declaration only Kotlin (and Scala) writes so.
            Signal(
                """^[ \t]*+(?:(?:private|internal|public|protected|override|const|lateinit|open)[ \t]++){0,3}""" +
                    """va[lr][ \t]++\w++[ \t]*+:[ \t]*+[A-Z]""",
                50,
            ),
            Signal("""^[ \t]*+val[ \t]++\w++[ \t]*+=""", 15),
            Signal("""^package[ \t]++[\w.]++[ \t]*+$""", 30),
            Signal("""^import[ \t]++\w++\.[\w.]*+\*?(?<!\.)(?:[ \t]++as[ \t]++\w++)?[ \t]*+$""", 15),
            Signal(
                """^[ \t]*+(?:(?:private|internal|public)[ \t]++)?(?:data class|sealed (?:class|interface)""" +
                    """|enum class|companion object|object[ \t]++\w++[ \t]*+(?:[:{(]|$))""",
                35,
            ),
            Signal(
                """\bclass[ \t]++\w++[ \t]*+\([ \t]*+(?:(?:private|protected|internal|override)[ \t]++)?va[lr][ \t]""",
                40,
            ),
            // `!!` before `.` or `)`, or ending an assignment's line; the elvis operator between blanks.
            Signal("""\w!![.)]|=[ \t]*+[\w.()\[\]"]++!![ \t]*+$|[\w)][ \t]++\?:[ \t]""", 20),
            Signal("""(?<![.\w])println\(""", 15),
            Signal("""\bwhen[ \t]*+(?:\([^)\n]*+\))?[ \t]*+\{""", 15),
            Signal("""\b(?:listOf|mapOf|setOf|mutableListOf|mutableMapOf|emptyList)\(""", 15),
        )

    private val java =
        listOf(
            Signal("""^[ \t]*+package[ \t]++[\w.]++[ \t]*+;""", 35),
            Signal("""^[ \t]*+import[ \t]++(?:static[ \t]++)?[\w.]++\*?[ \t]*+;""", 25),
            Signal(
                """^[ \t]*+(?:(?:public|protected|private|abstract|final|static|sealed)[ \t]++){1,4}""" +
                    """(?:class|interface|enum|record|@interface)[ \t]++\w""",
                35,
            ),
            Signal("""\bpublic[ \t]++static[ \t]++void[ \t]++main[ \t]*+\(""", 30),
            Signal("""\bSystem\.(?:out|err)\.print""", 30),
            // A method: modifiers, a return type and a name, parameters without `:` (not TypeScript).
            Signal(
                """^[ \t]*+(?:(?:public|protected|private|static|final|abstract|synchronized)[ \t]++){1,4}""" +
                    """(?!(?:fun|val|var|async|class|interface|enum|record)\b)[\w.]++(?:<[^>\n]*+>++)?(?:\[]){0,3}""" +
                    """[ \t]++\w++[ \t]*+\([^):\n]*+\)""",
                25,
            ),
            // A typed field or local ending in `;`: `private final Repo repo;`, `String key = order.id();`.
            Signal(
                """^[ \t]*+(?:(?:private|protected|public|static|final)[ \t]++){0,3}""" +
                    """(?:[A-Z]\w*+(?:<[^>\n]*+>++)?|int|long|boolean|double|float|char|byte|short)(?:\[]){0,3}""" +
                    """[ \t]++\w++[ \t]*+(?:=[^;\n]*+)?;[ \t]*+$""",
                20,
            ),
            Signal("""^[ \t]*+@Override\b""", 25),
            // The enhanced for loop with a typed variable: `for (Item item : items)`.
            Signal("""\bfor[ \t]*+\([ \t]*+(?:final[ \t]++)?[A-Z]\w*+(?:<[^>\n]*+>++)?[ \t]++\w++[ \t]*+:""", 25),
        )

    override fun scores(text: String): List<TypeScore> =
        heuristic(TextTypes.KOTLIN, weigh(text, kotlin)) + heuristic(TextTypes.JAVA, weigh(text, java))
}
