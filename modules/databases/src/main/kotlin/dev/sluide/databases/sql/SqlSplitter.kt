package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind

/** A statement of a console's text: its text without the trailing `;` and surrounding whitespace, and its range [start, end) in the whole text. */
data class SqlStatement(
    val text: String,
    val start: Int,
    val end: Int,
)

/** Splitting of console text into statements and classification of statements. `DELIMITER` is not supported (plain text). */
object SqlSplitter {
    /** The statements of [sql], split at `;` outside strings, quoted identifiers and comments. Blank/comment-only pieces are dropped. */
    fun split(
        sql: String,
        kind: DatabaseKind,
    ): List<SqlStatement> =
        pieces(sql, kind).mapNotNull { (from, to) ->
            var s = from
            var e = to
            while (s < e && sql[s].isWhitespace()) s++
            while (e > s && sql[e - 1].isWhitespace()) e--
            if (s == e) null else SqlStatement(sql.substring(s, e), s, e)
        }

    /** The raw pieces [start, end) between the `;` separators (the `;` excluded), blank and comment-only ones dropped. */
    internal fun pieces(
        sql: String,
        kind: DatabaseKind,
    ): List<Pair<Int, Int>> {
        val regions = SqlLexer.regions(sql, kind)
        val out = mutableListOf<Pair<Int, Int>>()
        var rp = 0
        var pieceStart = 0

        fun emit(end: Int) {
            while (rp < regions.size && regions[rp].end <= pieceStart) rp++
            var i = pieceStart
            var content = false
            var r = rp
            while (!content && r < regions.size && regions[r].start < end) {
                val region = regions[r]
                content = hasText(sql, i, region.start) || region.type != RegionType.COMMENT
                i = maxOf(i, region.end)
                r++
            }
            if (content || hasText(sql, i, end)) out += pieceStart to end
        }

        var r = 0
        var i = 0
        while (i < sql.length) {
            if (r < regions.size && regions[r].start == i) {
                i = regions[r].end
                r++
            } else {
                if (sql[i] == ';') {
                    emit(i)
                    pieceStart = i + 1
                }
                i++
            }
        }
        emit(sql.length)
        return out
    }

    private fun hasText(
        sql: String,
        from: Int,
        to: Int,
    ): Boolean = (from until to).any { !sql[it].isWhitespace() }

    /**
     * Whether [sql] returns rows and changes nothing, so a console may run it without asking. True for `SELECT`
     * (not `SELECT … INTO`, not `FOR UPDATE/SHARE/NO KEY UPDATE/KEY SHARE`), `VALUES`, `TABLE`, `SHOW`,
     * `DESCRIBE`/`DESC`, `WITH` (unless `insert`, `update`, `delete` or `merge` appears at any depth, or one of the
     * `SELECT` exclusions) and a plain `EXPLAIN` (false when `analyze` or `analyse` is in its head, as that runs the
     * statement). Leading comments and parentheses are skipped; everything else is false.
     */
    fun isQuery(
        sql: String,
        kind: DatabaseKind,
    ): Boolean {
        val words = codeWords(sql, kind)
        val first = words.firstOrNull()?.word ?: return false
        return when (first) {
            "values", "table", "show", "describe", "desc" -> true
            "select" -> !intoOrLocks(words)
            "with" -> words.none { it.word in CHANGES } && !intoOrLocks(words)
            "explain" -> {
                val rest = words.drop(1)
                val starter = rest.indexOfFirst { it.depth == 0 && it.word in STARTERS }
                val head = if (starter < 0) rest else rest.take(starter)
                head.none { it.word == "analyze" || it.word == "analyse" }
            }

            else -> false
        }
    }

    /** `SELECT … INTO` (at any depth) creates a table or writes a file; `FOR UPDATE/SHARE/…` and `LOCK IN SHARE MODE` lock rows. */
    private fun intoOrLocks(words: List<Word>): Boolean =
        words.any { it.word == "into" } ||
            words.zipWithNext().any { (a, b) ->
                (a.word == "for" && b.word in LOCK_WORDS) || (a.word == "lock" && b.word == "in")
            }

    private val CHANGES = setOf("insert", "update", "delete", "merge")
    private val LOCK_WORDS = setOf("update", "share", "no", "key")
    private val STARTERS = CHANGES + setOf("select", "replace", "with", "values", "table")

    private class Word(
        val word: String,
        val depth: Int,
    )

    /** The lowercase words of [sql] that are code (not strings, quoted identifiers or comments), with parenthesis depth. */
    private fun codeWords(
        sql: String,
        kind: DatabaseKind,
    ): List<Word> {
        val regions = SqlLexer.regions(sql, kind)
        val out = mutableListOf<Word>()
        var r = 0
        var depth = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                r < regions.size && regions[r].start == i -> i = regions[r++].end

                kind == DatabaseKind.MARIADB && c == '/' && SqlLexer.executableCommentEnd(sql, i) > 0 -> {
                    i = SqlLexer.executableCommentEnd(sql, i)
                }

                c == '(' -> {
                    depth++
                    i++
                }

                c == ')' -> {
                    depth--
                    i++
                }

                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                    out += Word(sql.substring(i, j).lowercase(), depth)
                    i = j
                }

                else -> i++
            }
        }
        return out
    }
}
