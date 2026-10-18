package dev.sluide.httpclient.envedit

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.io.StringWriter
import java.math.BigDecimal

/** A decimal number that keeps the spelling it was written with (`1e3` stays `1e3`, not `1E+3`). */
internal class SpelledDecimalNode(
    value: BigDecimal,
    val spelling: String,
) : DecimalNode(value)

/** A file of [EnvJson.parse] that is not valid JSON; [message] is Jackson's message without the location. */
internal class EnvJsonException(
    message: String,
) : Exception(message)

/**
 * Reads and writes environment files: key order kept, numbers with their spelling (floats as [BigDecimal], with
 * [SpelledDecimalNode] where `BigDecimal.toString` would spell them differently; integers as their value, so only `-0`
 * becomes `0`), duplicate keys refused.
 * Written with 2-space indentation, like the response view's pretty-printer, and a trailing newline.
 */
internal object EnvJson {
    val nodes: JsonNodeFactory = JsonNodeFactory.instance

    private val mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()

    private val prettyPrinter =
        DefaultPrettyPrinter()
            .withSeparators(
                Separators
                    .createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("")
                    .withArrayEmptySeparator(""),
            ).withObjectIndenter(DefaultIndenter("  ", "\n"))
            .withArrayIndenter(DefaultIndenter("  ", "\n"))

    /**
     * The single JSON value of [text], null for blank text; throws [EnvJsonException] if it is not valid JSON or holds
     * a number out of range.
     */
    fun parse(text: String): JsonNode? =
        try {
            mapper.createParser(text).use { parser ->
                if (parser.nextToken() == null) return null
                val node = read(parser)
                if (parser.nextToken() != null) throw EnvJsonException("Unexpected content after the end of the value")
                node
            }
        } catch (e: JacksonException) {
            throw EnvJsonException(e.originalMessage)
        }

    /** [text] as a JSON number node keeping its spelling, or null if it is not a JSON number. */
    fun number(text: String): JsonNode? =
        try {
            mapper.createParser(text.trim()).use { parser ->
                val token = parser.nextToken()
                if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) return null
                read(parser).takeIf { parser.nextToken() == null }
            }
        } catch (_: JacksonException) {
            null
        } catch (_: EnvJsonException) {
            null
        }

    /** A number node as written in JSON. */
    fun numberText(node: JsonNode): String =
        when (node) {
            is SpelledDecimalNode -> node.spelling
            is DecimalNode -> node.decimalValue().toString()
            else -> node.numberValue().toString()
        }

    /** [node] pretty-printed with a trailing newline. */
    fun print(node: JsonNode): String {
        val writer = StringWriter()
        mapper
            .writer()
            .with(prettyPrinter)
            .createGenerator(writer)
            .use { write(it, node) }
        return "$writer\n"
    }

    private fun read(parser: JsonParser): JsonNode =
        when (val token = parser.currentToken()) {
            JsonToken.START_OBJECT -> {
                val node = nodes.objectNode()
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    val name = parser.currentName()
                    parser.nextToken()
                    node.set(name, read(parser))
                }
                node
            }

            JsonToken.START_ARRAY -> {
                val node = nodes.arrayNode()
                while (parser.nextToken() != JsonToken.END_ARRAY) node.add(read(parser))
                node
            }

            JsonToken.VALUE_STRING -> {
                nodes.stringNode(parser.string)
            }

            JsonToken.VALUE_NUMBER_INT -> {
                when (parser.numberType) {
                    JsonParser.NumberType.INT -> nodes.numberNode(parser.intValue)
                    JsonParser.NumberType.LONG -> nodes.numberNode(parser.longValue)
                    else -> nodes.numberNode(parser.bigIntegerValue)
                }
            }

            JsonToken.VALUE_NUMBER_FLOAT -> {
                val text = parser.text
                val value =
                    try {
                        BigDecimal(text)
                    } catch (_: NumberFormatException) {
                        throw EnvJsonException("Number out of range: $text")
                    }
                if (value.toString() == text) DecimalNode(value) else SpelledDecimalNode(value, text)
            }

            JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE -> {
                nodes.booleanNode(token == JsonToken.VALUE_TRUE)
            }

            JsonToken.VALUE_NULL -> {
                nodes.nullNode()
            }

            else -> {
                throw EnvJsonException("Unexpected $token")
            }
        }

    private fun write(
        generator: JsonGenerator,
        node: JsonNode,
    ) {
        when {
            node is ObjectNode -> {
                generator.writeStartObject()
                for ((name, value) in node.properties()) {
                    generator.writeName(name)
                    write(generator, value)
                }
                generator.writeEndObject()
            }

            node is ArrayNode -> {
                generator.writeStartArray()
                node.values().forEach { write(generator, it) }
                generator.writeEndArray()
            }

            node.isNumber -> {
                generator.writeNumber(numberText(node))
            }

            node.isString -> {
                generator.writeString(node.stringValue())
            }

            node.isBoolean -> {
                generator.writeBoolean(node.booleanValue())
            }

            else -> {
                generator.writeNull()
            }
        }
    }
}
