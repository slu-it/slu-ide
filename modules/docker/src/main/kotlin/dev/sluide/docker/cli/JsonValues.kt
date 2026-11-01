package dev.sluide.docker.cli

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val log = System.getLogger("dev.sluide.docker.cli.JsonValues")

/** Reads the CLIs' JSON. */
internal val dockerJson: JsonMapper = JsonMapper.builder().build()

/** The most a single JSON value may grow to before the splitter drops it, as a guard against endless garbage. */
private const val MAX_VALUE_CHARS = 64 shl 20

private const val ESC = '\u001b'

/**
 * Cuts a character stream into its top-level JSON objects and arrays, whatever comes between them: one object per
 * line (Docker's `--format json`), pretty-printed arrays (Podman's), and the terminal escapes that `docker stats`
 * writes between its samples. Characters outside any value are skipped, so a stray text line does not break the
 * stream. Not thread-safe; feed it from one reader.
 */
internal class JsonValueSplitter(
    private val onValue: (String) -> Unit,
) {
    private val value = StringBuilder()
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var escape = EscapeState.NONE

    private enum class EscapeState { NONE, START, CSI }

    fun feed(text: CharSequence) {
        for (c in text) feed(c)
    }

    fun feed(c: Char) {
        if (!inString && skipEscape(c)) return
        if (depth == 0) {
            if (c == '{' || c == '[') {
                depth = 1
                value.append(c)
            }
            return
        }
        value.append(c)
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            return
        }
        when (c) {
            '"' -> inString = true
            '{', '[' -> depth++
            '}', ']' -> depth--
        }
        if (depth == 0) {
            val text = value.toString()
            value.setLength(0)
            onValue(text)
        } else if (value.length > MAX_VALUE_CHARS) {
            log.log(System.Logger.Level.WARNING, "Dropped a JSON value of more than $MAX_VALUE_CHARS characters")
            value.setLength(0)
            depth = 0
        }
    }

    /** Whether [c] belongs to a terminal escape sequence (`ESC [ … final`, or `ESC` and one character). */
    private fun skipEscape(c: Char): Boolean =
        when (escape) {
            EscapeState.NONE -> {
                if (c == ESC) escape = EscapeState.START
                c == ESC
            }

            EscapeState.START -> {
                escape = if (c == '[') EscapeState.CSI else EscapeState.NONE
                true
            }

            EscapeState.CSI -> {
                if (c in '@'..'~') escape = EscapeState.NONE
                true
            }
        }
}

/**
 * The JSON objects in [text]: each top-level object, and each element of a top-level array (Podman lists in one array
 * what Docker prints one per line). Values that do not parse are skipped and logged.
 */
internal fun jsonObjects(text: CharSequence): List<JsonNode> {
    val objects = mutableListOf<JsonNode>()
    val splitter = JsonValueSplitter { value -> objects += objectsOf(value) }
    splitter.feed(text)
    return objects
}

/** The objects of one top-level JSON [value]: itself, or the elements of an array. */
internal fun objectsOf(value: String): List<JsonNode> {
    val node =
        try {
            dockerJson.readTree(value)
        } catch (e: JacksonException) {
            log.log(System.Logger.Level.DEBUG, "Skipped JSON that does not parse: ${e.originalMessage}")
            return emptyList()
        }
    return if (node.isArray) node.values().filter { it.isObject } else listOf(node).filter { it.isObject }
}
