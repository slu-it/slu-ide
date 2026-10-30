package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.StringNode

/**
 * CSV (RFC 4180) with a guessed delimiter, `,` or `;`. Parsed by [CsvTable], not by Jackson's CSV module, which
 * reports no char offsets and cannot guess a delimiter.
 */
internal object CsvFormat : DataFormat by DelimitedFormat(
    id = "csv",
    name = "CSV",
    extensions = listOf("csv"),
    delimiter = null,
)

/** TSV: CSV with a tab as delimiter. */
internal object TsvFormat : DataFormat by DelimitedFormat(
    id = "tsv",
    name = "TSV",
    extensions = listOf("tsv", "tab"),
    delimiter = '\t',
)

/** The one implementation of CSV and TSV; a `null` [delimiter] means CSV (guessed on reading, an option on writing). */
internal class DelimitedFormat(
    override val id: String,
    override val name: String,
    override val extensions: List<String>,
    private val delimiter: Char?,
) : DataFormat {
    override val language: String? = null

    override val readOptions: List<ConversionOption> = listOf(DataOptions.csvHeader, DataOptions.inferTypes)
    override val writeOptions: List<ConversionOption> =
        listOfNotNull(DataOptions.csvDelimiter.takeIf { delimiter == null }, DataOptions.sortKeys)

    private val candidates = delimiter?.let { listOf(it) } ?: listOf(',', ';')

    override fun detect(text: String): Int? = candidates.mapNotNull { score(text.removePrefix(BOM), it) }.maxOrNull()

    override fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent> {
        val bom = if (text.startsWith(BOM)) BOM.length else 0
        val delimiter = delimiter ?: CsvTable.guessDelimiter(text, bom)
        val table = CsvTable.parse(text, bom, delimiter)
        if (table.errorOffset != null) return ReadResult.Failed("Quoted field is not closed", table.errorOffset)
        val rows = table.rows
        if (rows.isEmpty()) return ReadResult.Read(DataContent(emptyList()), emptyList())
        val infer = options.flag(DataOptions.inferTypes)
        return if (options.flag(DataOptions.csvHeader)) {
            readRecords(rows, infer)
        } else {
            val array = nodes.arrayNode()
            rows.forEach { row -> array.add(nodes.arrayNode().apply { row.forEach { add(cell(it, infer)) } }) }
            ReadResult.Read(DataContent(listOf(array)), emptyList())
        }
    }

    private fun readRecords(
        rows: List<List<String>>,
        infer: Boolean,
    ): ReadResult<DataContent> {
        val header = rows.first()
        val names = UniqueNames()
        var renamed = 0
        val keys =
            header.mapIndexed { index, cell ->
                val key = names.unique(cell.ifEmpty { "column${index + 1}" })
                if (cell.isNotEmpty() && key != cell) renamed++
                key
            }
        var ragged = 0
        val array = nodes.arrayNode()
        rows.drop(1).forEach { row ->
            if (row.size != header.size) ragged++
            val record = nodes.objectNode()
            keys.forEachIndexed { index, key ->
                record.set(key, if (index < row.size) cell(row[index], infer) else nodes.nullNode())
            }
            for (index in header.size until row.size) {
                var key = "column${index + 1}"
                while (record.has(key)) key += "_"
                record.set(key, cell(row[index], infer))
            }
            array.add(record)
        }
        val notes = ArrayList<String>()
        if (renamed > 0) notes += counted(renamed, "duplicate column name renamed", "duplicate column names renamed")
        if (ragged > 0) {
            val rowsText = if (ragged == 1) "row doesn't" else "rows don't"
            notes += "$ragged $rowsText match the header's ${header.size} columns"
        }
        return ReadResult.Read(DataContent(listOf(array)), notes)
    }

    private fun cell(
        text: String,
        infer: Boolean,
    ): JsonNode =
        when {
            !infer -> StringNode.valueOf(text)
            text.isEmpty() -> nodes.nullNode()
            else -> inferScalar(text)
        }

    override fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult {
        val documents = content.documents
        if (documents.isEmpty()) return WriteResult.Written("", emptyList())
        if (documents.size > 1) return WriteResult.Failed("CSV holds one table, not ${documents.size} documents")
        val root = documents.single()
        val delimiter = delimiter ?: options.choice(DataOptions.csvDelimiter).single()
        val notes = ArrayList<String>()
        val rows = ArrayList<List<String>>()
        val items: List<JsonNode> =
            when {
                root.isArray -> root.toList()
                root.isObject -> listOf(root)
                else -> return needsList()
            }
        when {
            items.isEmpty() -> {
                return WriteResult.Written("", emptyList())
            }

            items.all { it.isObject } -> {
                val flat = Flattener()
                val records = items.map { flat.record(it) }
                val sorted = options.flag(DataOptions.sortKeys)
                val names = if (sorted) flat.columns.keys.sortedWith(keyOrder) else flat.columns.keys.toList()
                if (names.isEmpty()) return WriteResult.Written("", emptyList())
                rows.add(names)
                records.forEach { record -> rows.add(names.map { record[it].orEmpty() }) }
                val nested = flat.columns.values.count { it }
                if (nested > 0) notes += "Nested values written as ${counted(nested, "column", "columns")}"
                if (flat.renamed.isNotEmpty()) {
                    notes += counted(flat.renamed.size, "column name made unique", "column names made unique")
                }
                if (flat.empties > 0) {
                    notes +=
                        counted(
                            flat.empties,
                            "empty object or list written as an empty cell",
                            "empty objects or lists written as empty cells",
                        )
                }
            }

            items.all { it.isArray } -> {
                var nested = 0
                items.forEach { row ->
                    rows.add(
                        row.toList().map { value ->
                            if (value.isContainer) {
                                nested++
                                value.toString()
                            } else {
                                text(value)
                            }
                        },
                    )
                }
                if (nested > 0) {
                    notes += counted(nested, "nested value written as JSON", "nested values written as JSON")
                }
            }

            items.none { it.isContainer } -> {
                rows.add(listOf("value"))
                items.forEach { rows.add(listOf(text(it))) }
            }

            else -> {
                return needsList()
            }
        }
        val out = StringBuilder()
        rows.forEach { row ->
            // A line with nothing on it would be skipped by the reader, so a lone empty cell is quoted.
            val lone = row.size <= 1 && row.firstOrNull().isNullOrEmpty()
            val line = if (lone) "\"\"" else row.joinToString(delimiter.toString()) { quote(it, delimiter) }
            out.append(line).append('\n')
        }
        return WriteResult.Written(out.toString(), notes)
    }

    private fun needsList() = WriteResult.Failed("CSV needs a list of records")

    /** Flattens records into cells by column name; collects the columns (`true`: from a nested value). */
    private inner class Flattener {
        val columns = LinkedHashMap<String, Boolean>()
        val renamed = LinkedHashSet<String>()
        var empties = 0

        // One name per origin (literal key or nested path) for all records, so a column means the same everywhere.
        private val names = UniqueNames()
        private val columnOf = HashMap<String, String>()

        fun record(item: JsonNode): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            item.properties().forEach { (key, value) -> put(key, "k$key", value, false, values) }
            return values
        }

        private fun put(
            path: String,
            origin: String,
            node: JsonNode,
            nested: Boolean,
            values: MutableMap<String, String>,
        ) {
            when {
                node.isObject && node.size() > 0 -> {
                    node.properties().forEach { (key, value) ->
                        put("$path.$key", "$origin\u0000k$key", value, true, values)
                    }
                }

                node.isArray && node.size() > 0 -> {
                    node.forEachIndexed {
                        index,
                        value,
                        ->
                        put("$path[$index]", "$origin\u0000i$index", value, true, values)
                    }
                }

                else -> {
                    val column =
                        columnOf.getOrPut(origin) {
                            names.unique(path).also { if (it != path) renamed.add(it) }
                        }
                    if (node.isContainer) empties++
                    columns.putIfAbsent(column, nested)
                    values[column] = if (node.isContainer) "" else text(node)
                }
            }
        }
    }

    private fun text(node: JsonNode): String =
        when {
            node.isNull || node.isMissingNode -> ""
            node.isBigDecimal -> node.decimalValue().toPlainString()
            else -> node.asString()
        }

    private fun quote(
        field: String,
        delimiter: Char,
    ): String =
        if (needsQuotes(field, delimiter)) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    private fun needsQuotes(
        field: String,
        delimiter: Char,
    ): Boolean {
        val special = field.any { it == delimiter || it == '"' || it == '\r' || it == '\n' }
        return special || field.startsWith(' ') || field.endsWith(' ')
    }

    /** 70 if every record has the same number (at least 2) of fields, 30 if most have two or more, else `null`. */
    private fun score(
        text: String,
        delimiter: Char,
    ): Int? {
        val table = CsvTable.parse(text, 0, delimiter, DETECT_RECORDS)
        val rows = table.rows
        if (table.errorOffset != null || rows.size < 2) return null
        val counts = rows.map { it.size }
        val score =
            when {
                counts.distinct().size == 1 && counts.first() >= 2 -> 70
                counts.count { it >= 2 } * 2 > counts.size -> 30
                else -> return null
            }
        // Properties, YAML and JSON lines with commas in them are not tables.
        val otherFormat =
            otherSyntax.containsMatchIn(rows.first().first()) || rows.all { otherSyntax.containsMatchIn(it.first()) }
        return if (otherFormat) minOf(score, 30) else score
    }

    private companion object {
        val otherSyntax = Regex("""^\s*(-\s|[\[{]|"[^"]*"\s*:|[^,;\t"=:\s][^,;\t"=:]*=|[^,;\t"]*:(\s|$))""")
        val nodes: JsonNodeFactory = JsonNodeFactory.instance
        const val BOM = "\uFEFF"
        const val DETECT_RECORDS = 1000
    }
}

/** Hands out names that are not used yet: `a`, then `a_2`, `a_3`. */
private class UniqueNames {
    private val used = HashSet<String>()
    private val next = HashMap<String, Int>()

    fun unique(base: String): String {
        if (used.add(base)) return base
        var n = next[base] ?: 2
        while (!used.add("${base}_$n")) n++
        next[base] = n + 1
        return "${base}_$n"
    }
}

/** CSV text as rows of cells. */
internal class CsvTable(
    val rows: List<List<String>>,
    /** Char offset of the opening quote of a field that is never closed; the rows are those before it. */
    val errorOffset: Int?,
) {
    companion object {
        /** The delimiter, `,` or `;`, that gives the same number (at least 2) of columns in each of the first 20 records. */
        fun guessDelimiter(
            text: String,
            start: Int,
        ): Char =
            listOf(',', ';').firstOrNull { delimiter ->
                val rows = parse(text, start, delimiter, 20).rows
                rows.isNotEmpty() && rows.map { it.size }.distinct().let { it.size == 1 && it.single() >= 2 }
            } ?: ','

        /**
         * Parses [text] from [start], at most [limit] records. Quotes open only at a field start, a quote in the
         * middle of a field is text; blank lines are skipped.
         */
        fun parse(
            text: String,
            start: Int,
            delimiter: Char,
            limit: Int = Int.MAX_VALUE,
        ): CsvTable {
            val rows = ArrayList<List<String>>()
            var row = ArrayList<String>()
            val field = StringBuilder()
            var quoted = false

            fun endRecord() {
                if (row.isNotEmpty() || field.isNotEmpty() || quoted) {
                    row.add(field.toString())
                    rows.add(row)
                    row = ArrayList()
                }
                field.setLength(0)
                quoted = false
            }

            var i = start
            while (i < text.length && rows.size < limit) {
                val char = text[i]
                when {
                    char == '"' && field.isEmpty() && !quoted -> {
                        var j = i + 1
                        while (true) {
                            val close = text.indexOf('"', j)
                            if (close < 0) return CsvTable(rows, i)
                            field.append(text, j, close)
                            if (text.startsWith("\"\"", close)) {
                                field.append('"')
                                j = close + 2
                            } else {
                                i = close
                                break
                            }
                        }
                        quoted = true
                    }

                    char == delimiter -> {
                        row.add(field.toString())
                        field.setLength(0)
                        quoted = false
                    }

                    char == '\r' || char == '\n' -> {
                        if (char == '\r' && text.startsWith("\n", i + 1)) i++
                        endRecord()
                    }

                    else -> {
                        field.append(char)
                    }
                }
                i++
            }
            if (rows.size < limit) endRecord()
            return CsvTable(rows, null)
        }
    }
}
