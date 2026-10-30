package dev.sluide.converting.data

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import tools.jackson.databind.JsonNode
import java.io.StringWriter

/**
 * Writes documents as block-style YAML with SnakeYAML's emitter (ADR 0115): strings any YAML 1.1 or 1.2 reader would
 * take for something else are quoted ([QuotingResolver]), multi-line strings become literal blocks, several documents
 * are separated by `---`.
 */
internal object YamlWriter {
    fun write(
        documents: List<JsonNode>,
        indent: Int,
    ): String {
        val options =
            DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                this.indent = indent
                // `key:` then `  - item` with the item's content at the indent; SnakeYAML gets there by different
                // settings for 2 and 4.
                indicatorIndent = 2
                indentWithIndicator = indent == 2
                splitLines = false
                isAllowUnicode = true
                lineBreak = DumperOptions.LineBreak.UNIX
            }
        val yaml =
            Yaml(SafeConstructor(LoaderOptions()), Representer(options), options, LoaderOptions(), QuotingResolver())
        return documents.joinToString("---\n") { document ->
            val text = StringWriter().also { yaml.serialize(node(document), it) }.toString()
            if (document.isArray) unindented(text) else text
        }
    }

    /**
     * A sequence at the root gets the indicator indent too (`  - a`); moving every line left by it keeps the structure,
     * as all lines move alike.
     */
    private fun unindented(text: String): String {
        val lines = text.removeSuffix("\n").split("\n")
        return if (lines.all { it.isEmpty() || it.startsWith("  ") }) {
            lines.joinToString("\n", postfix = "\n") { it.removePrefix("  ") }
        } else {
            text
        }
    }

    private fun node(value: JsonNode): Node =
        when {
            value.isObject -> {
                MappingNode(
                    Tag.MAP,
                    value.properties().map { (key, child) -> NodeTuple(scalar(Tag.STR, key), node(child)) },
                    DumperOptions.FlowStyle.BLOCK,
                )
            }

            value.isArray -> {
                SequenceNode(Tag.SEQ, buildList { value.forEach { add(node(it)) } }, DumperOptions.FlowStyle.BLOCK)
            }

            value.isNull -> {
                scalar(Tag.NULL, "null")
            }

            value.isBoolean -> {
                scalar(Tag.BOOL, value.booleanValue().toString())
            }

            value.isIntegralNumber -> {
                scalar(Tag.INT, value.bigIntegerValue().toString())
            }

            value.isBigDecimal -> {
                decimal(value.decimalValue().toString())
            }

            value.isNumber -> {
                double(value.doubleValue())
            }

            else -> {
                scalar(Tag.STR, value.asString())
            }
        }

    /** A decimal without a point or exponent (`!!float 1` read from YAML) would read back as an integer: write it so. */
    private fun decimal(text: String) =
        scalar(if (text.any { it == '.' || it == 'e' || it == 'E' }) Tag.FLOAT else Tag.INT, text)

    private fun double(value: Double) =
        scalar(
            Tag.FLOAT,
            when {
                value.isNaN() -> ".nan"
                value == Double.POSITIVE_INFINITY -> ".inf"
                value == Double.NEGATIVE_INFINITY -> "-.inf"
                else -> value.toString()
            },
        )

    private fun scalar(
        tag: Tag,
        text: String,
    ) = ScalarNode(tag, text, null, null, if (tag == Tag.STR) style(text) else DumperOptions.ScalarStyle.PLAIN)

    /**
     * Multi-line strings as literal blocks, except those starting with a space or a line break: their block needs an
     * indentation indicator, which SnakeYAML gets wrong for list items at indent 4. Strings with Unicode line breaks
     * are double-quoted, which escapes them; plain or in a block they would read back as spaces or other breaks.
     */
    private fun style(text: String): DumperOptions.ScalarStyle =
        when {
            text.any { it in UNICODE_BREAKS } -> DumperOptions.ScalarStyle.DOUBLE_QUOTED
            '\n' !in text -> DumperOptions.ScalarStyle.PLAIN
            text.startsWith(' ') || text.startsWith('\n') -> DumperOptions.ScalarStyle.DOUBLE_QUOTED
            else -> DumperOptions.ScalarStyle.LITERAL
        }

    /** NEL, LINE SEPARATOR and PARAGRAPH SEPARATOR. */
    private const val UNICODE_BREAKS = "\u0085\u2028\u2029"
}
