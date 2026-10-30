package dev.sluide.converting.data

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigInteger

/**
 * Works around Jackson's TOML parser, which reads decimal integers of exactly 19 digits wrongly (it drops digits;
 * jackson-dataformat-toml 3.1.5, `TomlParser.parseIntFromBuffer` calling `NumberInput.parseLong`; remove this after
 * an upgrade that fixes it). Such literals are replaced in the text by quoted strings of the same length, and the
 * integers put back after reading, so that error offsets stay valid. A literal that is a key (`[1234567890123456789]`,
 * `1234567890123456789 . a = 1`) cannot be told from a value without parsing, so it is masked too and its key put
 * back by its original text.
 */
internal class TomlIntegers private constructor(
    val text: String,
    private val masked: Map<String, String>,
) {
    /** [node] with the masked strings turned back into integers and the masked keys into their text. */
    fun restore(node: JsonNode): JsonNode {
        if (masked.isEmpty()) return node
        val root = restored(node)
        val pending = ArrayDeque<JsonNode>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val container = pending.removeLast()) {
                is ObjectNode -> {
                    val entries = container.properties().toList()
                    val renaming = entries.any { it.key in masked }
                    if (renaming) container.removeAll()
                    entries.forEach { (key, value) ->
                        val child = restored(value)
                        val name = masked[key] ?: key
                        // Masking hid that `1234567890123456789` and `"1234567890123456789"` are the same key.
                        check(!renaming || !container.has(name)) { "Duplicate key $name" }
                        container.set(name, child)
                        pending.addLast(child)
                    }
                }

                is ArrayNode -> {
                    for (i in 0 until container.size()) {
                        val child = restored(container.get(i))
                        container.set(i, child)
                        pending.addLast(child)
                    }
                }
            }
        }
        return root
    }

    private fun restored(node: JsonNode): JsonNode =
        if (node.isString) {
            masked[node.asString()]?.let {
                integerNode(
                    BigInteger(it.replace("_", "")),
                )
            } ?: node
        } else {
            node
        }

    companion object {
        private const val PAD = ''
        private val literal = Regex("[+-]?[0-9](?:_?[0-9])*")
        private const val TOKEN_CHARS = "_.:-"

        fun mask(text: String): TomlIntegers {
            var out: StringBuilder? = null
            val masked = HashMap<String, String>()
            val placeholders = HashMap<String, String>()
            var copied = 0
            var i = 0
            while (i < text.length) {
                val skipTo = skipStringOrComment(text, i)
                if (skipTo > i) {
                    i = skipTo
                    continue
                }
                val match = if (startsToken(text, i)) literal.matchAt(text, i) else null
                if (match == null) {
                    i++
                    continue
                }
                val end = match.range.last + 1
                val digits = match.value.filter { it.isDigit() }
                // Not masked: Jackson still rejects a leading zero, which a placeholder string would hide.
                val leadingZero = digits.length > 1 && digits.startsWith("0")
                // A signed literal as a key stays invalid, as Jackson reads it.
                val signedKey = match.value.startsWith("+") && isKeyEnd(text, end)
                if (digits.length == 19 && !leadingZero && !signedKey && endsToken(text, end)) {
                    val builder = out ?: StringBuilder().also { out = it }
                    builder.append(text, copied, i)
                    // One placeholder per literal, so that Jackson still sees a repeated key.
                    val placeholder =
                        placeholders.getOrPut(match.value) {
                            "$PAD${placeholders.size}".padEnd(match.value.length - 2, PAD)
                        }
                    builder.append('"').append(placeholder).append('"')
                    masked[placeholder] = match.value.removePrefix("+")
                    copied = end
                }
                i = end
            }
            val builder = out ?: return TomlIntegers(text, masked)
            builder.append(text, copied, text.length)
            return TomlIntegers(builder.toString(), masked)
        }

        private fun startsToken(
            text: String,
            at: Int,
        ): Boolean =
            (text[at].isDigit() || text[at] == '+' || text[at] == '-') &&
                (at == 0 || !(text[at - 1].isLetterOrDigit() || text[at - 1] in TOKEN_CHARS + "+"))

        /** Whether a key can end at [at]: the next character after blanks is `=` or `.`. */
        private fun isKeyEnd(
            text: String,
            at: Int,
        ): Boolean {
            var i = at
            while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
            return i < text.length && (text[i] == '=' || text[i] == '.')
        }

        private fun endsToken(
            text: String,
            at: Int,
        ): Boolean = at >= text.length || !(text[at].isLetterOrDigit() || text[at] in TOKEN_CHARS)

        /** The index after the string or comment starting at [at], or [at] if none starts there. */
        private fun skipStringOrComment(
            text: String,
            at: Int,
        ): Int =
            when {
                text.startsWith("\"\"\"", at) -> TomlText.endOfMultiLineBasic(text, at + 3)
                text.startsWith("'''", at) -> text.indexOf("'''", at + 3).let { if (it < 0) text.length else it + 3 }
                text[at] == '"' -> TomlText.endOfString(text, at + 1, '"', escapes = true)
                text[at] == '\'' -> TomlText.endOfString(text, at + 1, '\'', escapes = false)
                text[at] == '#' -> text.indexOf('\n', at).let { if (it < 0) text.length else it }
                else -> at
            }
    }
}
