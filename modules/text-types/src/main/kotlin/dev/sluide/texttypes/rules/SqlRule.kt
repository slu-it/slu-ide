package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * SQL by statements at a statement start: the first line, or a line after one that ends with `;` or after a blank
 * line (`--` comment lines left out). A statement counts with its shape (`SELECT … FROM`, `INSERT INTO`,
 * `UPDATE … SET`, `DELETE FROM`, `CREATE TABLE|VIEW|INDEX`, `ALTER TABLE`, `WITH … AS (`; any case) and one sign that
 * it is not a sentence: a `;` at its end, its first keyword in capitals, a `*` between `SELECT` and `FROM`, or a
 * comparison in a clause (`WHERE a = 1`, `SET a = 1`); a `*` list item or an `=` elsewhere in the paragraph does not
 * count, and a statement that ends in `.`, `?` or `!` is a sentence.
 */
internal object SqlRule : TextTypeSource {
    const val ONE_STATEMENT = 80
    const val STATEMENTS = 90

    private val shape =
        Regex(
            """\A(?:SELECT\s.*?\sFROM\s++\S|INSERT\s++INTO\s++\S|UPDATE\s++\S++\s++SET\s++\S|DELETE\s++FROM\s++\S""" +
                """|CREATE\s++(?:OR\s++REPLACE\s++)?(?:TEMP(?:ORARY)?\s++)?(?:UNIQUE\s++)?(?:TABLE|VIEW|INDEX)\s""" +
                """|ALTER\s++TABLE\s++\S|WITH\s++(?:RECURSIVE\s++)?\w++\s++AS\s*+\()""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

    /** A comparison after `WHERE`, `SET`, `ON`, `AND`, `OR`: where SQL puts its `=`. */
    private val clause =
        Regex("""\b(?:WHERE|SET|ON|AND|OR)\s++[\w.]++\s*+(?:=|<>|!=|<=|>=|<|>)""", RegexOption.IGNORE_CASE)

    private val from = Regex("""\sFROM\s""", RegexOption.IGNORE_CASE)

    override fun scores(text: String): List<TypeScore> =
        when (statements(text).filter(::isStatement).take(2).count()) {
            2 -> listOf(TypeScore(TextTypes.SQL, STATEMENTS))
            1 -> listOf(TypeScore(TextTypes.SQL, ONE_STATEMENT))
            else -> emptyList()
        }

    private fun isStatement(statement: String): Boolean {
        if (!shape.containsMatchIn(statement) || statement.last() in ".?!") return false
        val keyword = statement.takeWhile(Char::isLetter)
        return statement.endsWith(";") ||
            keyword == keyword.uppercase() ||
            selectsStar(keyword, statement) ||
            clause.containsMatchIn(statement)
    }

    /** A `*` between `SELECT` and its `FROM`: `SELECT *`, `SELECT t.*`, `SELECT count(*)`. */
    private fun selectsStar(
        keyword: String,
        statement: String,
    ): Boolean = keyword.equals("select", ignoreCase = true) && '*' in statement.split(from, 2).first()

    /** The trimmed lines from each statement start up to the next, joined by `\n`; `--` comment lines left out. */
    private fun statements(text: String): List<String> {
        val statements = mutableListOf(mutableListOf<String>())
        for (line in text.lineSequence().map(String::trim)) {
            when {
                line.isEmpty() -> {
                    statements += mutableListOf<String>()
                }

                line.startsWith("--") -> {
                    continue
                }

                else -> {
                    statements.last() += line
                    if (line.endsWith(";")) statements += mutableListOf<String>()
                }
            }
        }
        return statements.map { it.joinToString("\n") }
    }
}
