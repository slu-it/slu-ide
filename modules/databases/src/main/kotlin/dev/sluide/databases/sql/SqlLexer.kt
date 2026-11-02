package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind

internal enum class RegionType { STRING, IDENTIFIER, COMMENT }

/**
 * A string, quoted identifier or comment of SQL text, range [start, end). [closed]: it ended with its terminator
 * (line comments never count as closed: they end at the line break, and a caret at their end is still inside).
 */
internal data class Region(
    val type: RegionType,
    val start: Int,
    val end: Int,
    val closed: Boolean,
)

internal fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/** Finds the non-code regions of SQL text by the quoting and comment rules of a [DatabaseKind]. */
internal object SqlLexer {
    /** The regions of [sql] in order; everything between them is code. Unterminated ones run to the end. */
    fun regions(
        sql: String,
        kind: DatabaseKind,
    ): List<Region> {
        val maria = kind == DatabaseKind.MARIADB
        val out = mutableListOf<Region>()
        val n = sql.length
        var i = 0
        while (i < n) {
            val c = sql[i]
            val next = if (i + 1 < n) sql[i + 1] else ' '
            val executableEnd = if (maria && c == '/' && next == '*') executableCommentEnd(sql, i) else 0
            i =
                when {
                    c == '\'' -> {
                        val eString = !maria && i > 0 && sql[i - 1] in "eE" && (i < 2 || !isIdentChar(sql[i - 2]))
                        quoted(sql, i, RegionType.STRING, maria || eString, out)
                    }

                    c == '"' -> {
                        quoted(sql, i, if (maria) RegionType.STRING else RegionType.IDENTIFIER, maria, out)
                    }

                    c == '`' && maria -> {
                        quoted(sql, i, RegionType.IDENTIFIER, false, out)
                    }

                    c == '-' && next == '-' && (!maria || dashCommentStarts(sql, i)) -> {
                        lineComment(sql, i, out)
                    }

                    c == '#' && maria -> {
                        lineComment(sql, i, out)
                    }

                    executableEnd > 0 -> {
                        executableEnd
                    }

                    c == '/' && next == '*' -> {
                        blockComment(sql, i, !maria, out)
                    }

                    c == '$' && !maria && (i == 0 || !isIdentChar(sql[i - 1])) -> {
                        dollarQuote(sql, i, out)
                    }

                    else -> {
                        i + 1
                    }
                }
        }
        return out
    }

    /**
     * MariaDB executable comments `/*! … */` and `/*M! … */` hold code: returns the offset after their opening
     * (`/*!40101`), or 0 when [i] does not start one. The opener and the closing `*/` stay in the code stream (they are
     * no region); readers of code words skip the opener with this function.
     */
    internal fun executableCommentEnd(
        sql: String,
        i: Int,
    ): Int {
        var j = i + 2
        if (sql.startsWith("M!", j)) {
            j += 2
        } else if (sql.startsWith("!", j)) {
            j += 1
        } else {
            return 0
        }
        while (j < sql.length && sql[j].isDigit()) j++
        return j
    }

    /** MariaDB: `--` starts a comment only before whitespace, a control character or the end. */
    private fun dashCommentStarts(
        sql: String,
        i: Int,
    ): Boolean = i + 2 >= sql.length || sql[i + 2].isWhitespace() || sql[i + 2].isISOControl()

    private fun quoted(
        sql: String,
        start: Int,
        type: RegionType,
        backslash: Boolean,
        out: MutableList<Region>,
    ): Int {
        val q = sql[start]
        val n = sql.length
        var j = start + 1
        var closed = false
        while (j < n) {
            val ch = sql[j]
            if (backslash && ch == '\\') {
                j += 2
            } else if (ch == q) {
                if (j + 1 < n && sql[j + 1] == q) {
                    j += 2
                } else {
                    j++
                    closed = true
                    break
                }
            } else {
                j++
            }
        }
        val end = minOf(j, n)
        out += Region(type, start, end, closed)
        return end
    }

    private fun lineComment(
        sql: String,
        start: Int,
        out: MutableList<Region>,
    ): Int {
        val nl = sql.indexOf('\n', start)
        val end = if (nl < 0) sql.length else nl
        out += Region(RegionType.COMMENT, start, end, false)
        return end
    }

    private fun blockComment(
        sql: String,
        start: Int,
        nests: Boolean,
        out: MutableList<Region>,
    ): Int {
        val n = sql.length
        var j = start + 2
        var depth = 1
        var closed = false
        while (j < n) {
            if (nests && sql.startsWith("/*", j)) {
                depth++
                j += 2
            } else if (sql.startsWith("*/", j)) {
                j += 2
                if (--depth == 0) {
                    closed = true
                    break
                }
            } else {
                j++
            }
        }
        val end = if (closed) j else n
        out += Region(RegionType.COMMENT, start, end, closed)
        return end
    }

    /** `$$…$$` or `$tag$…$tag$`; a `$` that does not open one (`$1`) is plain code. */
    private fun dollarQuote(
        sql: String,
        start: Int,
        out: MutableList<Region>,
    ): Int {
        var j = start + 1
        while (j < sql.length && (sql[j].isLetter() || sql[j] == '_' || (j > start + 1 && sql[j].isDigit()))) j++
        if (j >= sql.length || sql[j] != '$') return start + 1
        val tag = sql.substring(start, j + 1)
        val close = sql.indexOf(tag, j + 1)
        val end = if (close < 0) sql.length else close + tag.length
        out += Region(RegionType.STRING, start, end, close >= 0)
        return end
    }
}
