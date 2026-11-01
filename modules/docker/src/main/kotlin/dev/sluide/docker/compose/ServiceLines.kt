package dev.sluide.docker.compose

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.resolver.Resolver

/**
 * The 0-based line of each key under the top-level `services:` mapping of the compose file [text], by service name,
 * in the order of the file (the gutter's ▶ goes there). Best effort: invalid or partial YAML gives what was found
 * before the error (the composer reads the whole document first, so usually nothing), or an empty map; never throws.
 * Anchors and merge keys (`<<: *common`) do not disturb it; the keys are read as written, merges are not resolved.
 */
fun serviceLines(text: String): Map<String, Int> =
    try {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = true }
        val composer = Composer(ParserImpl(StreamReader(text), options), Resolver(), options)
        val root = if (composer.checkNode()) composer.node else null
        servicesOf(root)
    } catch (_: RuntimeException) {
        // YAMLException is one; so are the marked errors of the parser and the scanner
        emptyMap()
    }

private fun servicesOf(root: Node?): Map<String, Int> {
    if (root !is MappingNode) return emptyMap()
    val services = root.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == "services" }?.valueNode
    if (services !is MappingNode) return emptyMap()
    val lines = linkedMapOf<String, Int>()
    for (tuple in services.value) {
        val key = tuple.keyNode as? ScalarNode ?: continue
        // `<<: *base` under services merges services in; it is no service of its own
        if (key.value != "<<") lines.putIfAbsent(key.value, key.startMark.line)
    }
    return lines
}
