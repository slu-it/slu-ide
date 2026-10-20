package dev.sluide.fileviewers.json

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.io.JsonStringEncoder
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.json.JsonMapper

internal enum class JsonKind(
    val container: Boolean = false,
) {
    OBJECT(true),
    ARRAY(true),
    STRING,
    NUMBER,
    KEYWORD,
}

/** A JSON value; [text] is empty for containers, the spelling of the value otherwise; [line] is 0-based. */
internal class JsonValue(
    val kind: JsonKind,
    val key: String?,
    val text: String,
    val line: Int,
) {
    val children = ArrayList<JsonValue>()
}

/** The values before the first error (reported as such), or the first values up to the cap. */
internal class ParsedJson(
    val root: JsonValue?,
    val error: String?,
    val truncated: Boolean,
)

/** Parses JSON text (comments and trailing commas accepted) into [JsonValue]s with Jackson's streaming parser. */
internal object JsonTree {
    private val mapper =
        JsonMapper
            .builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()

    /** The parenthetical with the `[Source: …]` excerpt that Jackson appends to some messages. */
    private val sourceExcerpt = Regex("""\s*\([^()]*\[Source:.*$""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The values of [text] as a tree: all that precedes the first error (reported as such), or the first [maxValues].
     */
    fun parse(
        text: String,
        maxValues: Int,
    ): ParsedJson {
        var root: JsonValue? = null
        var error: String? = null
        var truncated = false
        val stack = ArrayList<JsonValue>()
        try {
            mapper.createParser(text).use { parser ->
                var key: String? = null
                var keyLine = 0
                var count = 0
                while (true) {
                    val token = parser.nextToken() ?: break
                    if (token == JsonToken.PROPERTY_NAME) {
                        key = parser.currentName()
                        keyLine = parser.currentTokenLocation().lineNr - 1
                        continue
                    }
                    if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                        stack.removeLast()
                        continue
                    }
                    if (stack.isEmpty() && root != null) {
                        error = "Unexpected content after the end of the value"
                        break
                    }
                    if (count >= maxValues) {
                        truncated = true
                        break
                    }
                    count++
                    val kind =
                        when (token) {
                            JsonToken.START_OBJECT -> JsonKind.OBJECT
                            JsonToken.START_ARRAY -> JsonKind.ARRAY
                            JsonToken.VALUE_STRING -> JsonKind.STRING
                            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> JsonKind.NUMBER
                            else -> JsonKind.KEYWORD
                        }
                    // A member starts at its key, an element or the root at its value.
                    val line = if (key != null) keyLine else parser.currentTokenLocation().lineNr - 1
                    val node = JsonValue(kind, key, if (kind.container) "" else parser.text, line)
                    key = null
                    if (stack.isEmpty()) root = node else stack.last().children += node
                    if (kind.container) stack += node
                }
            }
        } catch (e: JacksonException) {
            error = describe(e, stack.lastOrNull())
        }
        return ParsedJson(root, error, truncated)
    }

    /**
     * "Line 3, column 7: message" for [e], without Jackson's source excerpt; [open] is the innermost unclosed
     * container.
     */
    private fun describe(
        e: JacksonException,
        open: JsonValue?,
    ): String {
        val line = e.location?.lineNr ?: 0
        val where = if (line > 0) "Line $line, column ${e.location?.columnNr}: " else ""
        val message = e.originalMessage.orEmpty()
        if (open != null && message.startsWith("Unexpected end-of-input") &&
            ("expected close marker" in message || "within/between" in message)
        ) {
            val what = if (open.kind == JsonKind.OBJECT) "object" else "array"
            return where + "the $what opened on line ${open.line + 1} is not closed"
        }
        return where + message.replace(sourceExcerpt, "").substringBefore(" (start marker at").trim()
    }

    /** [text] as a JSON string literal. */
    fun quote(text: String): String =
        StringBuilder(text.length + 2)
            .append('"')
            .also { JsonStringEncoder.getInstance().quoteAsString(text, it) }
            .append('"')
            .toString()
}
