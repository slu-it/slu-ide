package dev.sluide.converting.data

import dev.sluide.converting.ReadResult
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.CommentEvent
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.reader.ReaderException
import org.yaml.snakeyaml.representer.Representer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.BooleanNode
import tools.jackson.databind.node.DecimalNode
import tools.jackson.databind.node.DoubleNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NullNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import java.io.StringReader
import java.math.BigDecimal
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Reads YAML into documents by the YAML 1.2 core schema (ADR 0115): SnakeYAML composes the node graph, this turns it
 * into [JsonNode]s, expanding aliases and resolving `<<` merge keys. Expansion is bounded by SnakeYAML's alias limit
 * and by [maxNodes] values in the result.
 */
internal class YamlReader(
    private val maxNodes: Int = MAX_NODES,
) {
    fun read(text: String): ReadResult<DataContent> =
        try {
            val converter = Converter()
            val documents =
                yaml(processComments = false)
                    .composeAll(StringReader(text))
                    .filterNot(::isEmptyDocument)
                    .map { converter.value(it, 0) }
            val comments = countComments(text)
            val notes =
                buildList {
                    if (comments > 0) add(counted(comments, "comment dropped", "comments dropped"))
                    if (converter.octals > 0) {
                        add(
                            counted(
                                converter.octals,
                                "number with a leading zero read as octal",
                                "numbers with a leading zero read as octal",
                            ),
                        )
                    }
                    converter.droppedTags.forEach { (tag, count) ->
                        add(if (count == 1) "Tag $tag dropped" else "Tag $tag dropped $count times")
                    }
                }
            ReadResult.Read(DataContent(documents), notes)
        } catch (e: Failure) {
            ReadResult.Failed(e.message, e.mark?.let { offset(text, it.index) })
        } catch (e: MarkedYAMLException) {
            ReadResult.Failed(sentence(e.problem ?: e.message), e.problemMark?.let { offset(text, it.index) })
        } catch (e: ReaderException) {
            ReadResult.Failed("Special characters are not allowed", offset(text, e.position))
        } catch (e: YAMLException) {
            ReadResult.Failed(sentence(e.message), null)
        }

    /** A document without content (`---` followed by nothing) is no document. */
    private fun isEmptyDocument(root: Node) =
        root is ScalarNode &&
            root.tag == Tag.NULL &&
            root.value.isEmpty() &&
            root.scalarStyle == DumperOptions.ScalarStyle.PLAIN &&
            root.anchor == null

    /** The comments of [text]; SnakeYAML reads them on a second pass only, as it composes no graph with them. */
    private fun countComments(text: String): Int =
        if ('#' !in text) {
            0
        } else {
            try {
                yaml(processComments = true).parse(StringReader(text)).count {
                    it is CommentEvent && it.commentType != CommentType.BLANK_LINE
                }
            } catch (_: YAMLException) {
                0
            }
        }

    private class Failure(
        override val message: String,
        val mark: Mark?,
    ) : RuntimeException(message)

    private inner class Converter {
        val droppedTags = LinkedHashMap<String, Int>()
        var octals = 0
        private val nodes = JsonNodeFactory.instance
        private val path: MutableSet<Node> = Collections.newSetFromMap(IdentityHashMap())
        private var count = 0

        fun value(
            node: Node,
            depth: Int,
        ): JsonNode {
            if (++count > maxNodes) throw Failure("The YAML expands to more than $maxNodes values", null)
            if (depth > MAX_DEPTH) throw Failure("The YAML is nested deeper than $MAX_DEPTH levels", node.startMark)
            if (!path.add(node)) throw Failure("An alias refers to its own anchor", node.startMark)
            try {
                return when (node) {
                    is ScalarNode -> scalar(node)
                    is SequenceNode -> sequence(node, depth)
                    is MappingNode -> mapping(node, depth)
                    else -> throw Failure("Unknown YAML node", node.startMark)
                }
            } finally {
                path.remove(node)
            }
        }

        private fun sequence(
            node: SequenceNode,
            depth: Int,
        ): JsonNode {
            if (node.tag != Tag.SEQ) dropTag(node.tag)
            return nodes.arrayNode().apply { node.value.forEach { add(value(it, depth + 1)) } }
        }

        private fun mapping(
            node: MappingNode,
            depth: Int,
        ): ObjectNode {
            if (node.tag != Tag.MAP) dropTag(node.tag)
            val keys = node.value.map { if (isMerge(it.keyNode)) null else key(it.keyNode) }
            val explicit = HashSet<String>()
            var merges = 0
            node.value.forEachIndexed { index, tuple ->
                val key = keys[index]
                val duplicate = if (key == null) merges++ > 0 else !explicit.add(key)
                if (duplicate) throw Failure("Duplicate key ${key ?: "<<"}", tuple.keyNode.startMark)
            }
            val result = nodes.objectNode()
            node.value.forEachIndexed { index, tuple ->
                val key = keys[index]
                if (key == null) {
                    merged(tuple.valueNode, depth).forEach { (mergedKey, value) ->
                        if (mergedKey !in explicit && !result.has(mergedKey)) result.set(mergedKey, value)
                    }
                } else {
                    result.set(key, value(tuple.valueNode, depth + 1))
                }
            }
            return result
        }

        /** The entries a merge key brings in; of several mappings, the first one that has a key wins. */
        private fun merged(
            node: Node,
            depth: Int,
        ): List<Map.Entry<String, JsonNode>> {
            val sources =
                when {
                    node is MappingNode -> listOf(node)
                    node is SequenceNode && node.value.all { it is MappingNode } -> node.value
                    else -> throw Failure("A merge key takes a mapping or a list of mappings", node.startMark)
                }
            return sources.flatMap { value(it, depth + 1).properties() }
        }

        private fun isMerge(key: Node) = key is ScalarNode && key.tag == Tag.MERGE

        /** A key as written: scalars of any type become their text; collections cannot be keys of JSON objects. */
        private fun key(node: Node): String {
            if (node !is ScalarNode) throw Failure("A key must be a scalar, not a collection", node.startMark)
            if (node.tag !in scalarTags) dropTag(node.tag)
            return node.value
        }

        private fun scalar(node: ScalarNode): JsonNode {
            val text = node.value
            val number = node.tag == Tag.INT || node.tag == Tag.FLOAT
            if (number && tooLongForNumber(text)) return StringNode.valueOf(text)
            return when (node.tag) {
                Tag.STR, Tag.MERGE -> {
                    StringNode.valueOf(text)
                }

                Tag.NULL -> {
                    NullNode.instance
                }

                Tag.BOOL -> {
                    when (text.lowercase()) {
                        "true" -> BooleanNode.TRUE
                        "false" -> BooleanNode.FALSE
                        else -> throw Failure("Not a boolean: $text", node.startMark)
                    }
                }

                Tag.INT -> {
                    integer(text) ?: throw Failure("Not an integer: $text", node.startMark)
                }

                Tag.FLOAT -> {
                    float(text) ?: throw Failure("Not a number: $text", node.startMark)
                }

                Tag.BINARY -> {
                    StringNode.valueOf(text.filterNot(Char::isWhitespace))
                }

                else -> {
                    dropTag(node.tag)
                    StringNode.valueOf(text)
                }
            }
        }

        private fun integer(text: String): JsonNode? {
            val negative = text.startsWith("-")
            val digits = text.removePrefix("-").removePrefix("+")
            val value =
                when {
                    digits.startsWith("0o") -> digits.substring(2).toBigIntegerOrNull(8)
                    digits.startsWith("0x") -> digits.substring(2).toBigIntegerOrNull(16)
                    digits.length > 1 && digits.startsWith("0") -> digits.toBigIntegerOrNull(8)?.also { octals++ }
                    else -> digits.toBigIntegerOrNull()
                } ?: return null
            return integerNode(if (negative) value.negate() else value)
        }

        private fun float(text: String): JsonNode? =
            when (text.removePrefix("+")) {
                ".inf", ".Inf", ".INF" -> DoubleNode.valueOf(Double.POSITIVE_INFINITY)
                "-.inf", "-.Inf", "-.INF" -> DoubleNode.valueOf(Double.NEGATIVE_INFINITY)
                ".nan", ".NaN", ".NAN" -> DoubleNode.valueOf(Double.NaN)
                else -> decimalOrNull(text)?.let(DecimalNode::valueOf)
            }

        private fun dropTag(tag: Tag) {
            val name = if (tag.value.startsWith(Tag.PREFIX)) "!!" + tag.value.removePrefix(Tag.PREFIX) else tag.value
            droppedTags.merge(name, 1, Int::plus)
        }
    }

    companion object {
        /**
         * Values in the result beyond which reading fails: aliases can expand a short text enormously (an 822-byte bomb
         * gave 19.5 MB of JSON at 1 000 000). This, not SnakeYAML's alias count, bounds the expansion.
         */
        const val MAX_NODES = 100_000

        /** Nesting beyond this fails, as Jackson's own limit would when writing the tree. */
        const val MAX_DEPTH = 500

        private val scalarTags = setOf(Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.MERGE)

        private fun yaml(processComments: Boolean): Yaml {
            val options =
                LoaderOptions().apply {
                    setProcessComments(processComments)
                    // GitLab CI and Compose files merge `<<: *defaults` in every job; MAX_NODES bounds the expansion.
                    maxAliasesForCollections = 10_000
                    nestingDepthLimit = MAX_DEPTH
                    codePointLimit = 3 * 1024 * 1024
                }
            val dumperOptions = DumperOptions()
            return Yaml(
                SafeConstructor(options),
                Representer(dumperOptions),
                dumperOptions,
                options,
                CoreSchemaResolver(),
            )
        }

        /** SnakeYAML counts code points; editors count chars. */
        private fun offset(
            text: String,
            codePoints: Int,
        ): Int = text.offsetByCodePoints(0, codePoints.coerceIn(0, text.codePointCount(0, text.length)))
    }
}

private fun decimalOrNull(text: String): BigDecimal? =
    try {
        BigDecimal(text)
    } catch (_: NumberFormatException) {
        null
    }
