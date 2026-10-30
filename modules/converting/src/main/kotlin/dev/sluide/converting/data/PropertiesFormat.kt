package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Java properties in Spring Boot's dialect: nested keys, `[0]` lists and `#---` between documents (ADR 0115). */
internal object PropertiesFormat : DataFormat {
    override val id = "properties"
    override val name = "Properties"
    override val language = "properties"
    override val extensions = listOf("properties")

    private val bracketedKeyPart = Regex("^\\[[^\\]\\[\"{}]+]")

    override val readOptions: List<ConversionOption> = listOf(DataOptions.inferTypes)
    override val writeOptions: List<ConversionOption> = listOf(DataOptions.sortKeys, DataOptions.escapeNonAscii)

    override fun detect(text: String): Int? {
        if (PropertiesReader.read(text, inferTypes = false) !is ReadResult.Read) return null
        val entries = PropertiesReader.scan(text).documents.flatten()
        if (entries.isEmpty() || entries.any { it.separator != '=' && it.separator != ':' }) return null
        // Broken JSON and flow YAML (`{"a": 1`, `["a": 1]`) have such keys; they are not properties.
        if (entries.any { startsLikeJson(it.key) }) return null
        return if (entries.all { it.separator == '=' }) 60 else 40
    }

    /** `{`, `"` and `[` start broken JSON or flow YAML, but `[a.b]` is a key part of ours (see [PropertiesWriter]). */
    private fun startsLikeJson(key: String): Boolean =
        key.first() in "{\"" || (key.first() == '[' && !bracketedKeyPart.containsMatchIn(key))

    override fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent> = PropertiesReader.read(text, options.flag(DataOptions.inferTypes))

    override fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult {
        val sort = options.flag(DataOptions.sortKeys)
        val roots = ArrayList<ObjectNode>()
        for (document in content.documents) {
            if (document !is ObjectNode) {
                val what = if (document.isArray) "an array" else "a value"
                return WriteResult.Failed("Properties need an object at the top, not $what")
            }
            roots += (if (sort) sortKeys(document) else document) as ObjectNode
        }
        return PropertiesWriter(options.flag(DataOptions.escapeNonAscii)).write(roots)
    }
}

/** Flattens objects to `key=value` lines. */
internal class PropertiesWriter(
    private val escapeNonAscii: Boolean,
) {
    private var nulls = 0
    private var empties = 0
    private var emptyDocuments = 0

    private class Unwritable(
        message: String,
    ) : Exception(message)

    fun write(documents: List<ObjectNode>): WriteResult {
        emptyDocuments = documents.count { it.size() == 0 }
        val sections =
            try {
                documents.filter { it.size() > 0 }.map { document ->
                    val lines = ArrayList<String>()
                    flatten(document, "", lines)
                    lines
                }
            } catch (e: Unwritable) {
                return WriteResult.Failed(e.message ?: "Cannot be written")
            }
        val text =
            sections.joinToString("#---\n") { lines -> lines.joinToString("") { "$it\n" } }
        val notes = ArrayList<String>()
        if (nulls > 0) notes += counted(nulls, "null written as an empty value", "nulls written as empty values")
        if (empties > 0) {
            notes +=
                counted(
                    empties,
                    "empty object or list written as an empty value",
                    "empty objects or lists written as empty values",
                )
        }
        if (emptyDocuments > 0) notes += counted(emptyDocuments, "empty document dropped", "empty documents dropped")
        return WriteResult.Written(text, notes)
    }

    private fun flatten(
        node: JsonNode,
        key: String,
        lines: MutableList<String>,
    ) {
        when {
            node.isObject && node.size() > 0 -> {
                node.properties().forEach { (name, child) -> flatten(child, key + keyPart(name, key.isEmpty()), lines) }
            }

            node.isArray && node.size() > 0 -> {
                node.forEachIndexed { index, child -> flatten(child, "$key[$index]", lines) }
            }

            else -> {
                val value =
                    when {
                        node.isNull -> "".also { nulls++ }
                        node.isContainer -> "".also { empties++ }
                        else -> node.asString()
                    }
                if (key.isNotEmpty()) lines += "$key=${escape(value, key = false)}"
            }
        }
    }

    /** `.name`, or `[name]` if the name has a dot or bracket (or none), which reads back as one part. */
    private fun keyPart(
        name: String,
        first: Boolean,
    ): String {
        val literal = name.isEmpty() || name.any { it == '.' || it == '[' }
        if (literal && ']' in name) {
            throw Unwritable("The key \"$name\" has a ] with a . or [ and cannot be written as a properties key")
        }
        return when {
            literal -> "[${escape(name, key = true)}]"
            first -> escape(name, key = true)
            else -> ".${escape(name, key = true)}"
        }
    }

    private fun escape(
        text: String,
        key: Boolean,
    ): String {
        val out = StringBuilder()
        text.forEachIndexed { i, char ->
            when {
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char == '\u000C' -> out.append("\\f")
                char == ' ' && (key || i == 0) -> out.append("\\ ")
                key && char in "=:#!" -> out.append('\\').append(char)
                char < ' ' || (escapeNonAscii && char > '~') -> out.append("\\u%04X".format(char.code))
                else -> out.append(char)
            }
        }
        return out.toString()
    }
}
