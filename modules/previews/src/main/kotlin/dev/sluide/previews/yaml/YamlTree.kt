package dev.sluide.previews.yaml

import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionEndEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.MappingStartEvent
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import org.yaml.snakeyaml.events.SequenceStartEvent
import org.yaml.snakeyaml.reader.ReaderException
import java.io.StringReader
import java.util.Locale

/**
 * A node of the YAML a preview shows. [properties] are its anchor and tag as written (`&base`, `!!str`, `!Ref`), in
 * source order; [line] is the 0-based source line it starts on (at its first property, if any).
 */
internal sealed class YamlNode(
    val properties: List<String>,
    val line: Int,
)

/**
 * A scalar as written: [lines] holds its text split at the line breaks of the source ([breaks], one fewer), the first
 * line as it follows its key or `-`, the others without their indentation (block scalar lines keep what they have
 * beyond the block's), see [YamlSource.scalarLines]; [value] is what it reads as, for paths. [valueLine] is the line
 * its text starts on.
 */
internal class YamlScalar(
    properties: List<String>,
    line: Int,
    val style: ScalarStyle,
    val value: String,
    val lines: List<String>,
    val breaks: List<String>,
    val valueLine: Int,
) : YamlNode(properties, line)

/** An alias (`*name`), never expanded. */
internal class YamlAlias(
    val name: String,
    line: Int,
) : YamlNode(emptyList(), line)

internal class YamlMapping(
    properties: List<String>,
    line: Int,
) : YamlNode(properties, line) {
    /** In source order, duplicate keys kept. */
    val entries = ArrayList<YamlEntry>()
}

internal class YamlSequence(
    properties: List<String>,
    line: Int,
) : YamlNode(properties, line) {
    val items = ArrayList<YamlNode>()
}

/** A key and its value; the value is missing only where an error or the node cap cut the mapping short. */
internal class YamlEntry(
    val key: YamlNode,
) {
    var value: YamlNode? = null
}

/**
 * A document: its directives as written (`%YAML 1.2`, without comments), whether it starts with `---` ([explicit]) and
 * the line of that marker, and its root node (missing only where an error or the node cap came first).
 */
internal class YamlDocument(
    val directives: List<String>,
    val explicit: Boolean,
    val line: Int,
) {
    var root: YamlNode? = null
}

/** The documents before the first error (reported as such) or the first `maxNodes` nodes, open containers closed. */
internal class ParsedYaml(
    val documents: List<YamlDocument>,
    val error: String?,
    val truncated: Boolean,
)

/**
 * Parses YAML text into [YamlDocument]s from SnakeYAML's event stream (ADR 0079): no node graph is composed and no type
 * constructed, so tags never instantiate classes and aliases are never expanded. Comments are not read.
 */
internal object YamlParser {
    /** Deeper nesting is an error; it keeps the page (and its recursion) bounded. */
    const val MAX_DEPTH = 1000

    /** `while parsing a block mapping`, the context SnakeYAML gives a problem. */
    private val context = Regex("""^while (?:parsing|scanning) (?:an? )?(.+)$""")

    private fun options() =
        LoaderOptions().apply {
            setProcessComments(false)
            // The default (3 MB) would reject large files the editor shows; the node cap bounds the page instead.
            codePointLimit = Int.MAX_VALUE
        }

    fun parse(
        text: String,
        maxNodes: Int,
    ): ParsedYaml {
        val source = YamlSource(text)
        val documents = ArrayList<YamlDocument>()
        val stack = ArrayList<YamlNode>()
        var error: String? = null
        var truncated = false
        var count = 0
        try {
            for (event in Yaml(options()).parse(StringReader(text))) {
                when (event) {
                    is DocumentStartEvent -> {
                        val start = source.offset(event.startMark)
                        val end = source.offset(event.endMark)
                        documents += YamlDocument(source.directives(start, end), event.explicit, source.line(end))
                    }

                    is CollectionEndEvent -> {
                        stack.removeLastOrNull()
                    }

                    is NodeEvent -> {
                        if (count >= maxNodes) {
                            truncated = true
                            break
                        }
                        val isCollection = event is MappingStartEvent || event is SequenceStartEvent
                        if (isCollection && stack.size >= MAX_DEPTH) {
                            error = source.where(event.startMark) + "nested deeper than $MAX_DEPTH levels"
                            break
                        }
                        count++
                        val node = node(event, source)
                        attach(node, stack.lastOrNull(), documents.lastOrNull() ?: break)
                        if (isCollection) stack += node
                    }

                    else -> {}
                }
            }
        } catch (e: YAMLException) {
            error = describe(e, source)
        }
        return ParsedYaml(documents, error, truncated)
    }

    private fun node(
        event: NodeEvent,
        source: YamlSource,
    ): YamlNode {
        val start = source.offset(event.startMark)
        val end = source.offset(event.endMark)
        val line = source.line(start)
        return when (event) {
            is AliasEvent -> {
                YamlAlias(event.anchor, line)
            }

            is ScalarEvent -> {
                val written = source.properties(start, end, event.anchor, event.tag)
                val text = source.scalarLines(written.valueStart, end, event.scalarStyle, event.value)
                val valueLine = source.line(minOf(written.valueStart, end))
                YamlScalar(written.properties, line, event.scalarStyle, event.value, text.lines, text.breaks, valueLine)
            }

            is MappingStartEvent -> {
                YamlMapping(source.properties(start, end, event.anchor, event.tag).properties, line)
            }

            is SequenceStartEvent -> {
                YamlSequence(source.properties(start, end, event.anchor, event.tag).properties, line)
            }

            else -> {
                error("unknown node event $event")
            }
        }
    }

    private fun attach(
        node: YamlNode,
        parent: YamlNode?,
        document: YamlDocument,
    ) {
        when (parent) {
            null -> {
                document.root = node
            }

            is YamlSequence -> {
                parent.items += node
            }

            is YamlMapping -> {
                // A value always follows its key (an empty one as an empty scalar), so a missing value is the next.
                val last = parent.entries.lastOrNull()
                if (last != null && last.value == null) last.value = node else parent.entries += YamlEntry(node)
            }

            else -> {
                error("scalars have no children")
            }
        }
    }

    /**
     * "Line 3, column 7: problem; the block mapping started on line 1" for [e], with the place of the problem (and of
     * its context, if on another line) instead of SnakeYAML's source excerpt.
     */
    private fun describe(
        e: YAMLException,
        source: YamlSource,
    ): String =
        when (e) {
            is MarkedYAMLException -> {
                val mark: Mark? = e.problemMark ?: e.contextMark
                val problem = (e.problem ?: e.context ?: "invalid YAML").trim()
                val contextMark = e.contextMark
                val started =
                    if (e.problem != null && e.context != null && contextMark != null && mark != null &&
                        source.line(source.offset(contextMark)) != source.line(source.offset(mark))
                    ) {
                        val what = context.find(e.context)?.groupValues?.get(1)
                        val line = source.line(source.offset(contextMark)) + 1
                        if (what != null) "; the $what started on line $line" else "; ${e.context} on line $line"
                    } else {
                        ""
                    }
                (mark?.let(source::where) ?: "") + problem + started
            }

            is ReaderException -> {
                source.whereCodePoint(e.position) +
                    "the character U+%04X is not allowed in YAML".format(Locale.ROOT, e.codePoint)
            }

            else -> {
                e.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { "invalid YAML" }
            }
        }
}
