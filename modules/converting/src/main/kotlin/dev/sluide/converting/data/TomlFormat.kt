package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.toml.TomlMapper

/** TOML 1.0, read by Jackson with exact numbers and written by [TomlWriter] as a person writes it (ADR 0115). */
internal object TomlFormat : DataFormat {
    override val id = "toml"
    override val name = "TOML"
    override val language = "toml"
    override val extensions = listOf("toml")

    override val readOptions: List<ConversionOption> = emptyList()
    override val writeOptions: List<ConversionOption> = listOf(DataOptions.sortKeys)

    private val mapper: TomlMapper =
        TomlMapper
            .builder()
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build()

    private const val KEY = """[A-Za-z0-9_-]+|"[^"]+"|'[^']+'"""
    private val header = Regex("""\s*\[\[?[^\[\]]+]]?\s*(#.*)?""")
    private val keyValue = Regex("""\s*($KEY)(\s*\.\s*($KEY))*\s*=.*""")
    private val spacedKeyValue = Regex("""\s*($KEY)(\s*\.\s*($KEY))*\s+=\s.*""")

    override fun detect(text: String): Int? {
        if (read(text, OptionValues()) !is ReadResult.Read) return null
        val lines = text.lines()
        return when {
            lines.any { header.matches(it) || spacedKeyValue.matches(it) } -> 90

            // Compact `server.port=8080` lines are far more likely properties, which then win with 60.
            lines.any { keyValue.matches(it) } -> 50

            else -> null
        }
    }

    override fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent> =
        try {
            val masked = TomlIntegers.mask(text)
            val node: JsonNode? = mapper.readTree(masked.text)?.let(masked::restore)
            val documents = if (node == null || node.isMissingNode) emptyList() else listOf(node)
            val comments = TomlText.countComments(text)
            val notes =
                if (comments > 0) listOf(counted(comments, "comment dropped", "comments dropped")) else emptyList()
            ReadResult.Read(DataContent(documents), notes)
        } catch (e: JacksonException) {
            val offset =
                e.location
                    ?.charOffset
                    ?.takeIf { it >= 0 }
                    ?.coerceAtMost(text.length.toLong())
                    ?.toInt()
            ReadResult.Failed(sentence(e.originalMessage), offset)
        } catch (e: RuntimeException) {
            ReadResult.Failed(sentence(e.message), null)
        }

    override fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult {
        val documents = content.documents
        if (documents.size > 1) return WriteResult.Failed("TOML holds one document, not ${documents.size}")
        var root = documents.single()
        if (root !is ObjectNode) {
            val what = if (root.isArray) "an array" else "a value"
            return WriteResult.Failed("TOML needs an object at the top, not $what")
        }
        if (options.flag(DataOptions.sortKeys)) root = sortKeys(root) as ObjectNode
        val pruned = TomlWriter.prune(root)
        val notes = ArrayList<String>()
        if (pruned.nulls > 0) notes += counted(pruned.nulls, "null dropped", "nulls dropped")
        if (pruned.bigIntegers > 0) {
            notes +=
                counted(
                    pruned.bigIntegers,
                    "integer beyond 64 bit written as a string",
                    "integers beyond 64 bit written as strings",
                )
        }
        return WriteResult.Written(TomlWriter.write(pruned.root), notes)
    }
}

/** Scans TOML text for what Jackson does not report: its comments. */
internal object TomlText {
    /** The number of `#` comments outside strings, multi-line strings included. */
    fun countComments(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i =
                when {
                    text.startsWith("\"\"\"", i) -> {
                        endOfMultiLineBasic(text, i + 3)
                    }

                    text.startsWith("'''", i) -> {
                        text.indexOf("'''", i + 3).let { if (it < 0) text.length else it + 3 }
                    }

                    text[i] == '"' -> {
                        endOfString(text, i + 1, '"', escapes = true)
                    }

                    text[i] == '\'' -> {
                        endOfString(text, i + 1, '\'', escapes = false)
                    }

                    text[i] == '#' -> {
                        count++
                        text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    }

                    else -> {
                        i + 1
                    }
                }
        }
        return count
    }

    /** The index after the closing quote of a one-line string whose content starts at [from]. */
    fun endOfString(
        text: String,
        from: Int,
        quote: Char,
        escapes: Boolean,
    ): Int {
        var i = from
        while (i < text.length && text[i] != '\n') {
            if (escapes && text[i] == '\\') {
                i++
            } else if (text[i] == quote) {
                return i + 1
            }
            i++
        }
        return i
    }

    fun endOfMultiLineBasic(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '\\') {
                i++
            } else if (text.startsWith("\"\"\"", i)) {
                return i + 3
            }
            i++
        }
        return text.length
    }
}
