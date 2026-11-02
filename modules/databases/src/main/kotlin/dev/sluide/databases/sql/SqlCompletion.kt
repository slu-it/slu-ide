package dev.sluide.databases.sql

import dev.sluide.databases.connect.DatabaseKind

enum class CompletionKind { KEYWORD, SCHEMA, TABLE, COLUMN }

/** [text] is what is inserted (quoted when needed), [label] what the popup shows. */
data class Completion(
    val text: String,
    val label: String,
    val kind: CompletionKind,
)

/**
 * [replaceFrom]: where the typed prefix starts; accepting an item replaces [replaceFrom, replaceTo). [replaceTo] is
 * the caret, or the end of the quoted identifier when the caret is inside a closed one (`"Or|ders"`).
 */
data class CompletionResult(
    val replaceFrom: Int,
    val replaceTo: Int,
    val items: List<Completion>,
)

/** The names the completion knows; non-blocking: returns what is cached (empty when not loaded yet; an implementation may start loading on a miss). */
interface CompletionCatalog {
    val defaultSchema: String?

    fun schemas(): List<String>

    /** Tables and views of [schema]; null = the default schema. */
    fun tables(schema: String?): List<String>

    fun columns(
        schema: String?,
        table: String,
    ): List<String>
}

object SqlCompletion {
    private const val MAX_ITEMS = 200

    /** A name as written in the statement, with whether it was quoted. */
    private class Name(
        val text: String,
        val quoted: Boolean,
    )

    private class TableRef(
        val schema: Name?,
        val table: Name,
        val alias: Name?,
    )

    private class Candidate(
        val name: String,
        val kind: CompletionKind,
    )

    fun candidates(
        text: String,
        caret: Int,
        kind: DatabaseKind,
        catalog: CompletionCatalog,
    ): CompletionResult {
        val regions = SqlLexer.regions(text, kind)
        val inside = insideRegion(regions, caret)
        if (inside != null && inside.type != RegionType.IDENTIFIER) return CompletionResult(caret, caret, emptyList())
        val quoted = inside != null
        val replaceFrom: Int
        val replaceTo: Int
        val prefix: String
        if (inside != null) {
            replaceFrom = inside.start
            replaceTo = if (inside.closed) inside.end else caret
            val q = text[inside.start]
            prefix = text.substring(inside.start + 1, caret).replace("$q$q", "$q")
        } else {
            var j = caret
            while (j > 0 && isIdentChar(text[j - 1])) j--
            replaceFrom = j
            replaceTo = caret
            prefix = text.substring(j, caret)
        }
        val refs = tablesOfStatementAt(text, regions, caret)
        val candidates =
            if (replaceFrom > 0 && text[replaceFrom - 1] == '.') {
                qualified(text, replaceFrom - 1, kind, catalog, refs, prefix)
            } else {
                unqualified(kind, catalog, refs, prefix, quoted)
            }
        val lowerKeywords = prefix.isNotEmpty() && prefix == prefix.lowercase()
        val items =
            candidates
                .distinctBy { if (it.kind == CompletionKind.KEYWORD) "\u0000" + it.name else it.name }
                .take(MAX_ITEMS)
                .map {
                    val insert =
                        when (it.kind) {
                            CompletionKind.KEYWORD -> if (lowerKeywords) it.name.lowercase() else it.name
                            else -> insertable(kind, it.name, quoted)
                        }
                    Completion(insert, it.name, it.kind)
                }.toList()
        return CompletionResult(replaceFrom, replaceTo, items)
    }

    /**
     * Whether typing just produced a reason to open the popup by itself: caret right after `.`, or after a word of 2+
     * identifier characters that starts with a letter or `_` (not `10` or `$12`), not inside a string or comment.
     */
    fun shouldAutoOpen(
        text: String,
        caret: Int,
        kind: DatabaseKind,
    ): Boolean {
        if (caret <= 0 || caret > text.length) return false
        val last = text[caret - 1]
        if (last != '.') {
            if (!isIdentChar(last)) return false
            var j = caret
            while (j > 0 && isIdentChar(text[j - 1])) j--
            if (caret - j < 2 || !(text[j].isLetter() || text[j] == '_')) return false
        }
        val inside = insideRegion(SqlLexer.regions(text, kind), caret)
        return inside == null || inside.type == RegionType.IDENTIFIER
    }

    private fun insideRegion(
        regions: List<Region>,
        caret: Int,
    ): Region? = regions.firstOrNull { caret > it.start && (caret < it.end || (caret == it.end && !it.closed)) }

    private fun unqualified(
        kind: DatabaseKind,
        catalog: CompletionCatalog,
        refs: List<TableRef>,
        prefix: String,
        quoted: Boolean,
    ): Sequence<Candidate> {
        fun names(
            source: Sequence<String>,
            completionKind: CompletionKind,
        ) = source.filter { it.startsWith(prefix, ignoreCase = true) }.map { Candidate(it, completionKind) }

        val columns =
            refs.asSequence().flatMap { ref ->
                val (schema, table) = resolve(kind, catalog, ref)
                names(catalog.columns(schema, table).asSequence(), CompletionKind.COLUMN)
            }
        val tables = names(catalog.tables(null).asSequence(), CompletionKind.TABLE)
        val schemas = names(catalog.schemas().asSequence(), CompletionKind.SCHEMA)
        val keywords =
            if (quoted) emptySequence() else names(SqlKeywords.all(kind).asSequence(), CompletionKind.KEYWORD)
        return columns + tables + schemas + keywords
    }

    private fun qualified(
        text: String,
        dot: Int,
        kind: DatabaseKind,
        catalog: CompletionCatalog,
        refs: List<TableRef>,
        prefix: String,
    ): Sequence<Candidate> {
        val qualifier = nameBefore(text, dot) ?: return emptySequence()
        val schemaPart =
            if (qualifier.start > 0 && text[qualifier.start - 1] == '.') nameBefore(text, qualifier.start - 1) else null

        fun candidates(
            names: List<String>,
            completionKind: CompletionKind,
        ) = names.asSequence().filter { it.startsWith(prefix, ignoreCase = true) }.map { Candidate(it, completionKind) }

        if (schemaPart != null) {
            val schema = resolveSchema(kind, catalog, schemaPart.name)
            val table = resolveTable(kind, catalog, schema, qualifier.name)
            return candidates(catalog.columns(schema, table), CompletionKind.COLUMN)
        }
        val ref =
            refs.firstOrNull { it.alias != null && sameName(kind, it.alias, qualifier.name) }
                ?: refs.firstOrNull { it.alias == null && sameName(kind, it.table, qualifier.name) }
                ?: refs.firstOrNull { sameName(kind, it.table, qualifier.name) }
        if (ref != null) {
            val (schema, table) = resolve(kind, catalog, ref)
            val columns = candidates(catalog.columns(schema, table), CompletionKind.COLUMN).toList()
            if (columns.isNotEmpty()) return columns.asSequence()
        }
        val schema = catalog.schemas().firstOrNull { sameName(kind, qualifier.name, Name(it, true)) }
        return if (schema == null) emptySequence() else candidates(catalog.tables(schema), CompletionKind.TABLE)
    }

    /**
     * PostgreSQL folds unquoted names to lowercase and compares quoted ones exactly; MariaDB ignores case. A catalog
     * name counts as quoted (it is the exact name).
     */
    private fun foldName(
        kind: DatabaseKind,
        name: Name,
    ): String = if (kind == DatabaseKind.MARIADB || !name.quoted) name.text.lowercase() else name.text

    private fun sameName(
        kind: DatabaseKind,
        a: Name,
        b: Name,
    ): Boolean = foldName(kind, a) == foldName(kind, b)

    /** The catalog's schema and table a reference names; the names as written when the catalog does not know them. */
    private fun resolve(
        kind: DatabaseKind,
        catalog: CompletionCatalog,
        ref: TableRef,
    ): Pair<String?, String> {
        val schema = ref.schema?.let { resolveSchema(kind, catalog, it) }
        return schema to resolveTable(kind, catalog, schema, ref.table)
    }

    private fun resolveSchema(
        kind: DatabaseKind,
        catalog: CompletionCatalog,
        name: Name,
    ): String = catalog.schemas().firstOrNull { sameName(kind, name, Name(it, true)) } ?: name.text

    private fun resolveTable(
        kind: DatabaseKind,
        catalog: CompletionCatalog,
        schema: String?,
        name: Name,
    ): String = catalog.tables(schema).firstOrNull { sameName(kind, name, Name(it, true)) } ?: name.text

    private class Before(
        val name: Name,
        val start: Int,
    )

    /** The identifier ending right before [end] with its start offset (quoted names unquoted), or null. */
    private fun nameBefore(
        text: String,
        end: Int,
    ): Before? {
        if (end <= 0) return null
        val last = text[end - 1]
        if (last == '"' || last == '`') {
            var k = end - 2
            while (k >= 0) {
                if (text[k] != last) {
                    k--
                } else if (k > 0 && text[k - 1] == last) {
                    k -= 2
                } else {
                    break
                }
            }
            if (k < 0) return null
            return Before(Name(text.substring(k + 1, end - 1).replace("$last$last", "$last"), true), k)
        }
        var j = end
        while (j > 0 && isIdentChar(text[j - 1])) j--
        return if (j == end) null else Before(Name(text.substring(j, end), false), j)
    }

    private fun insertable(
        kind: DatabaseKind,
        name: String,
        forceQuote: Boolean,
    ): String = if (forceQuote || needsQuote(kind, name)) SqlNames.quote(kind, name) else name

    private val PG_PLAIN = Regex("[a-z_][a-z0-9_$]*")
    private val MARIA_PLAIN = Regex("[A-Za-z_][A-Za-z0-9_$]*")

    private fun needsQuote(
        kind: DatabaseKind,
        name: String,
    ): Boolean {
        val plain = if (kind == DatabaseKind.MARIADB) MARIA_PLAIN else PG_PLAIN
        return !plain.matches(name) || name.lowercase() in SqlKeywords.RESERVED
    }

    // ---- tables of the current statement ----

    private val TABLE_KEYWORDS = setOf("from", "join", "update", "into")
    private val LIST_KEYWORDS = setOf("from", "update")
    private val STOP_WORDS =
        setOf(
            "where",
            "on",
            "set",
            "using",
            "join",
            "inner",
            "left",
            "right",
            "full",
            "cross",
            "natural",
            "group",
            "order",
            "having",
            "limit",
            "union",
            "values",
            "select",
            "outer",
            "window",
            "returning",
            "offset",
            "fetch",
            "for",
            "except",
            "intersect",
            "straight_join",
            "lateral",
            "partition",
            "with",
            "default",
        )

    private class Tok(
        val text: String,
        val type: Int,
    )

    private const val WORD = 0
    private const val QUOTED = 1
    private const val PUNCT = 2

    private fun tablesOfStatementAt(
        text: String,
        regions: List<Region>,
        caret: Int,
    ): List<TableRef> {
        val pieces = SqlSplitter.pieces(text, regions)
        val (from, to) = pieces.firstOrNull { caret in it.first..it.second } ?: return emptyList()
        val tokens = tokenize(text, regions, from, to)
        val out = mutableListOf<TableRef>()
        var k = 0
        while (k < tokens.size) {
            val t = tokens[k++]
            if (t.type == WORD && t.text.lowercase() in TABLE_KEYWORDS) {
                k = parseRefs(tokens, k, t.text.lowercase() in LIST_KEYWORDS, out)
            }
        }
        return out
    }

    private fun tokenize(
        sql: String,
        regions: List<Region>,
        from: Int,
        to: Int,
    ): List<Tok> {
        val out = mutableListOf<Tok>()
        var r = regions.indexOfFirst { it.end > from }.let { if (it < 0) regions.size else it }
        var i = from
        while (i < to) {
            if (r < regions.size && regions[r].start == i) {
                val region = regions[r++]
                when (region.type) {
                    RegionType.COMMENT -> {
                        Unit
                    }

                    RegionType.STRING -> {
                        out += Tok("'", PUNCT)
                    }

                    RegionType.IDENTIFIER -> {
                        val q = sql[region.start]
                        val inner = sql.substring(region.start + 1, if (region.closed) region.end - 1 else region.end)
                        out += Tok(inner.replace("$q$q", "$q"), QUOTED)
                    }
                }
                i = region.end
            } else if (sql[i].isWhitespace()) {
                i++
            } else if (isIdentChar(sql[i])) {
                var j = i
                while (j < to && isIdentChar(sql[j])) j++
                out += Tok(sql.substring(i, j), WORD)
                i = j
            } else {
                out += Tok(sql[i].toString(), PUNCT)
                i++
            }
        }
        return out
    }

    private fun isName(
        t: Tok?,
        allowStop: Boolean = false,
    ) = t != null && (t.type == QUOTED || (t.type == WORD && (allowStop || t.text.lowercase() !in STOP_WORDS)))

    private fun Tok.toName() = Name(text, type == QUOTED)

    /** Parses `table [AS] alias` (and `, table ...` for FROM/UPDATE) from [start]; returns the index after it. */
    private fun parseRefs(
        tokens: List<Tok>,
        start: Int,
        commaList: Boolean,
        out: MutableList<TableRef>,
    ): Int {
        var k = start
        while (true) {
            if (tokens.getOrNull(k)?.text.equals("only", ignoreCase = true)) k++
            if (!isName(tokens.getOrNull(k))) return k
            var schema: Name? = null
            var table = tokens[k++].toName()
            if (tokens.getOrNull(k)?.text == "." && isName(tokens.getOrNull(k + 1), allowStop = true)) {
                schema = table
                table = tokens[k + 1].toName()
                k += 2
            }
            var alias: Name? = null
            if (tokens.getOrNull(k)?.let { it.type == WORD && it.text.equals("as", ignoreCase = true) } == true) {
                k++
                if (isName(tokens.getOrNull(k), allowStop = true)) alias = tokens[k++].toName()
            } else if (isName(tokens.getOrNull(k))) {
                alias = tokens[k++].toName()
            }
            out += TableRef(schema, table, alias)
            if (commaList && tokens.getOrNull(k)?.text == ",") k++ else return k
        }
    }
}
