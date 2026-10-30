package dev.sluide.converting.data

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult

/** YAML, read by the YAML 1.2 core schema and written so that YAML 1.1 readers read the same (ADR 0115). */
internal object YamlFormat : DataFormat {
    override val id = "yaml"
    override val name = "YAML"
    override val language = "yaml"
    override val extensions = listOf("yaml", "yml")

    override val readOptions: List<ConversionOption> = emptyList()
    override val writeOptions: List<ConversionOption> = listOf(DataOptions.indent, DataOptions.sortKeys)

    private val reader = YamlReader()

    override fun detect(text: String): Int? {
        val read = read(text, OptionValues()) as? ReadResult.Read ?: return null
        val documents = read.content.documents
        return when {
            startsWithFlowCollection(text) -> 30
            documents.none { it.isObject || it.isArray } -> 10
            documents.size > 1 -> 85
            else -> 80
        }
    }

    override fun read(
        text: String,
        options: OptionValues,
    ): ReadResult<DataContent> = reader.read(text)

    override fun write(
        content: DataContent,
        options: OptionValues,
    ): WriteResult {
        val documents = if (options.flag(DataOptions.sortKeys)) content.documents.map(::sortKeys) else content.documents
        return WriteResult.Written(YamlWriter.write(documents, options.choice(DataOptions.indent).toInt()), emptyList())
    }

    /** JSON objects and arrays are YAML too, but JSON is the surer guess for them. */
    private fun startsWithFlowCollection(text: String): Boolean =
        text
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.let { it.startsWith("{") || it.startsWith("[") } == true
}
