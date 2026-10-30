package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.StringNode

/**
 * JSON, read leniently as JSONC (comments, trailing commas) but with duplicate keys and trailing content as errors;
 * numbers stay exact (ADR 0115).
 */
internal object JsonFormat : DataFormat {
    override val id = "json"
    override val name = "JSON"
    override val language = "json"
    override val extensions = listOf("json", "jsonc")

    override val readOptions: List<ConversionOption> = emptyList()
    override val writeOptions: List<ConversionOption> =
        listOf(DataOptions.indent, DataOptions.compact, DataOptions.sortKeys)

    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build()

    override fun detect(text: String): Int? {
        val first = JsonText.firstSignificant(text) ?: return null
        val read = read(text, OptionValues()) as? ReadResult.Read ?: return null
        return when {
            read.content.documents.isEmpty() -> null
            first == '{' || first == '[' -> 100
            else -> 20
        }
    }

    override fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent> =
        try {
            val node: JsonNode? = mapper.readTree(text)
            val documents = if (node == null || node.isMissingNode) emptyList() else listOf(node)
            val comments = JsonText.countComments(text)
            val notes = buildList { if (comments > 0) add(counted(comments, "comment dropped", "comments dropped")) }
            ReadResult.Read(DataContent(documents), notes)
        } catch (_: NumberFormatException) {
            // Jackson parses `1e99999999999` lazily into a BigDecimal, which cannot hold the exponent.
            ReadResult.Failed("A number is out of range", null)
        } catch (e: JacksonException) {
            val offset =
                e.location
                    ?.charOffset
                    ?.takeIf { it >= 0 }
                    ?.coerceAtMost(text.length.toLong())
                    ?.toInt()
            ReadResult.Failed(sentence(e.originalMessage), offset)
        }

    override fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult {
        val notes = ArrayList<String>()
        val documents = content.documents
        var root =
            if (documents.size == 1) {
                documents.single()
            } else {
                notes += "${documents.size} documents written as an array"
                JsonNodeFactory.instance.arrayNode().addAll(documents)
            }
        if (options.flag(DataOptions.sortKeys)) root = sortKeys(root)
        val nonFinite = NonFiniteCount()
        root = finite(root, nonFinite)
        if (nonFinite.count > 0) {
            notes +=
                counted(nonFinite.count, "NaN or infinity written as a string", "NaNs or infinities written as strings")
        }
        return try {
            val text =
                if (options.flag(DataOptions.compact)) {
                    mapper.writeValueAsString(root)
                } else {
                    mapper
                        .writer()
                        .with(
                            prettyPrinter(options.choice(DataOptions.indent).toInt()),
                        ).writeValueAsString(root) +
                        "\n"
                }
            WriteResult.Written(text, notes)
        } catch (e: JacksonException) {
            // Jackson limits what it writes, e.g. the nesting depth.
            WriteResult.Failed(sentence(e.originalMessage))
        }
    }

    private fun prettyPrinter(indent: Int): DefaultPrettyPrinter {
        val indenter = DefaultIndenter(" ".repeat(indent), "\n")
        val separators =
            Separators
                .createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("")
        return DefaultPrettyPrinter(separators).withObjectIndenter(indenter).withArrayIndenter(indenter)
    }

    private class NonFiniteCount {
        var count = 0
    }

    /** [node] with NaN and infinities (from YAML's `.nan`, `.inf`) as strings, which JSON cannot hold otherwise. */
    private fun finite(
        node: JsonNode,
        nonFinite: NonFiniteCount,
    ): JsonNode =
        when {
            node.isObject -> {
                val copy = JsonNodeFactory.instance.objectNode()
                node.properties().forEach { (key, value) -> copy.set(key, finite(value, nonFinite)) }
                copy
            }

            node.isArray -> {
                JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(finite(it, nonFinite)) } }
            }

            node.isFloatingPointNumber && !node.isBigDecimal && !node.doubleValue().isFinite() -> {
                nonFinite.count++
                val value = node.doubleValue()
                StringNode.valueOf(
                    when {
                        value.isNaN() -> "NaN"
                        value > 0 -> "Infinity"
                        else -> "-Infinity"
                    },
                )
            }

            else -> {
                node
            }
        }
}

/** Scans JSON text for what Jackson does not report: its comments and its first character that is not one. */
internal object JsonText {
    /** The number of `//` and `/* */` comments outside strings. */
    fun countComments(text: String): Int {
        var count = 0
        scan(text) { _, comment -> if (comment) count++ }
        return count
    }

    /** The first character that is neither whitespace nor part of a comment, or `null` if there is none. */
    fun firstSignificant(text: String): Char? {
        var first: Char? = null
        scan(text) { char, comment -> if (!comment && first == null) first = char }
        return first
    }

    /** Calls [visit] with each comment start (`true`) and each other non-blank character outside comments (`false`). */
    private inline fun scan(
        text: String,
        visit: (Char, Boolean) -> Unit,
    ) {
        var i = 0
        var inString = false
        while (i < text.length) {
            val char = text[i]
            when {
                inString -> {
                    visit(char, false)
                    if (char == '\\') {
                        i++
                    } else if (char == '"') {
                        inString = false
                    }
                }

                char == '/' && text.startsWith("//", i) -> {
                    visit(char, true)
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                }

                char == '/' && text.startsWith("/*", i) -> {
                    visit(char, true)
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 1 }
                }

                char == '"' -> {
                    visit(char, false)
                    inString = true
                }

                !char.isWhitespace() -> {
                    visit(char, false)
                }
            }
            i++
        }
    }
}

/** A message as a sentence: its first line, starting with a capital letter. */
internal fun sentence(message: String?): String =
    message
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.replaceFirstChar(Char::uppercaseChar)
        ?.takeIf(String::isNotEmpty) ?: "Cannot be read"
