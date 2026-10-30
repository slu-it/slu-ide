package dev.sluide.converting.data

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.math.BigDecimal

/**
 * Writes an object as TOML the way a person does: the scalars and inline arrays of a table first, then its sub-tables
 * as `[a.b]` sections and arrays of objects as `[[a.b]]` sections, each section after a blank line.
 */
internal object TomlWriter {
    private val nodes = JsonNodeFactory.instance
    private val bareKey = Regex("[A-Za-z0-9_-]+")

    /** [root] without nulls and with integers beyond 64 bit as strings, with how many of each. */
    class Pruned(
        val root: ObjectNode,
        val nulls: Int,
        val bigIntegers: Int,
    )

    fun prune(root: ObjectNode): Pruned {
        val counts = IntArray(2)
        return Pruned(prune(root, counts) as ObjectNode, counts[0], counts[1])
    }

    private fun prune(
        node: JsonNode,
        counts: IntArray,
    ): JsonNode =
        when {
            node.isObject -> {
                val copy = nodes.objectNode()
                node.properties().forEach { (key, value) ->
                    if (value.isNull) counts[0]++ else copy.set(key, prune(value, counts))
                }
                copy
            }

            node.isArray -> {
                val copy = nodes.arrayNode()
                node.forEach { if (it.isNull) counts[0]++ else copy.add(prune(it, counts)) }
                copy
            }

            node.isIntegralNumber && node.bigIntegerValue().bitLength() > 63 -> {
                counts[1]++
                StringNode.valueOf(node.bigIntegerValue().toString())
            }

            else -> {
                node
            }
        }

    /** The TOML text of [root], which [prune] has cleaned. */
    fun write(root: ObjectNode): String {
        val out = StringBuilder()
        writeTable(out, root, emptyList(), arrayItem = false)
        return out.toString()
    }

    private fun writeTable(
        out: StringBuilder,
        table: ObjectNode,
        path: List<String>,
        arrayItem: Boolean,
    ) {
        val (sections, inline) = table.properties().partition { isSection(it.value) }
        if (path.isNotEmpty() && (arrayItem || inline.isNotEmpty() || sections.isEmpty())) {
            if (out.isNotEmpty()) out.append('\n')
            val dotted = path.joinToString(".") { key(it) }
            out.append(if (arrayItem) "[[$dotted]]" else "[$dotted]").append('\n')
        }
        inline.forEach { (key, value) ->
            out
                .append(key(key))
                .append(" = ")
                .append(value(value, true))
                .append('\n')
        }
        sections.forEach { (key, value) ->
            if (value is ArrayNode) {
                value.forEach { writeTable(out, it as ObjectNode, path + key, arrayItem = true) }
            } else {
                writeTable(out, value as ObjectNode, path + key, arrayItem = false)
            }
        }
    }

    private fun isSection(node: JsonNode): Boolean =
        node.isObject || (node.isArray && node.size() > 0 && node.all { it.isObject })

    private fun key(key: String): String = if (bareKey.matches(key)) key else basicString(key)

    private fun value(
        node: JsonNode,
        multiLine: Boolean,
    ): String =
        when {
            node.isObject -> {
                if (node.size() == 0) {
                    "{}"
                } else {
                    node.properties().joinToString(", ", "{ ", " }") { (key, value) ->
                        "${key(key)} = ${value(value, false)}"
                    }
                }
            }

            node.isArray -> {
                node.joinToString(", ", "[", "]") { value(it, false) }
            }

            node.isBoolean -> {
                node.booleanValue().toString()
            }

            node.isIntegralNumber -> {
                node.bigIntegerValue().toString()
            }

            node.isFloatingPointNumber -> {
                float(node)
            }

            else -> {
                val text = node.asString()
                if (multiLine && text.contains('\n')) multiLineString(text) else basicString(text)
            }
        }

    private fun float(node: JsonNode): String {
        if (node.isBigDecimal) return decimal(node.decimalValue())
        val value = node.doubleValue()
        return when {
            value.isNaN() -> "nan"
            value == Double.POSITIVE_INFINITY -> "inf"
            value == Double.NEGATIVE_INFINITY -> "-inf"
            else -> decimal(BigDecimal(value.toString()))
        }
    }

    /** A float literal: integers written as decimals get `.0`, so that they read back as floats. */
    private fun decimal(value: BigDecimal): String {
        val text = value.toString()
        return if (text.none { it == '.' || it == 'E' }) "$text.0" else text
    }

    private fun basicString(text: String): String {
        val out = StringBuilder("\"")
        text.forEach { escape(out, it, quote = true) }
        return out.append('"').toString()
    }

    /** A `"""` string whose lines stay as they are; the quotes that would end it are escaped. */
    private fun multiLineString(text: String): String {
        val out = StringBuilder("\"\"\"\n")
        var quotes = 0
        var endsWithRawQuote = false
        text.forEach { char ->
            endsWithRawQuote = false
            when {
                char == '\n' -> {
                    out.append('\n')
                    quotes = 0
                }

                char == '"' -> {
                    quotes++
                    if (quotes >= 3) {
                        out.append("\\\"")
                        quotes = 0
                    } else {
                        out.append('"')
                        endsWithRawQuote = true
                    }
                }

                else -> {
                    quotes = 0
                    escape(out, char, quote = false)
                }
            }
        }
        if (endsWithRawQuote) {
            out.setLength(out.length - 1)
            out.append("\\\"")
        }
        return out.append("\"\"\"").toString()
    }

    private fun escape(
        out: StringBuilder,
        char: Char,
        quote: Boolean,
    ) {
        when {
            char == '"' && quote -> out.append("\\\"")
            char == '\\' -> out.append("\\\\")
            char == '\b' -> out.append("\\b")
            char == '\t' -> out.append("\\t")
            char == '\n' -> out.append("\\n")
            char == '\u000C' -> out.append("\\f")
            char == '\r' -> out.append("\\r")
            char < ' ' || char == '\u007F' -> out.append("\\u%04X".format(char.code))
            else -> out.append(char)
        }
    }
}
