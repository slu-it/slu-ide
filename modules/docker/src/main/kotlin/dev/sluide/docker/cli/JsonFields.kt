package dev.sluide.docker.cli

import tools.jackson.databind.JsonNode

/**
 * The first of [names] this object has, compared without case (Docker writes `ID`, Podman `Id` or `id`); `null` if
 * none, or if the value is JSON `null`.
 */
internal fun JsonNode.field(vararg names: String): JsonNode? {
    for (name in names) {
        val exact = get(name)
        if (exact != null) return exact.takeUnless { it.isNull }
        val loose = properties().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        if (loose != null) return loose.takeUnless { it.isNull }
    }
    return null
}

/** The text of the first of [names] (numbers and booleans as text); `null` if missing or blank. */
internal fun JsonNode.text(vararg names: String): String? =
    field(*names)?.takeIf { it.isValueNode }?.asString()?.takeIf { it.isNotBlank() }

/** The number of the first of [names], also when written as text; `null` if missing or not a number. */
internal fun JsonNode.long(vararg names: String): Long? {
    val node = field(*names) ?: return null
    return when {
        node.isNumber -> node.asLong()
        node.isValueNode -> node.asString().trim().toLongOrNull()
        else -> null
    }
}

/** The truth value of the first of [names], also when written as text; `null` if missing or not a truth value. */
internal fun JsonNode.bool(vararg names: String): Boolean? {
    val node = field(*names) ?: return null
    return when {
        node.isBoolean -> {
            node.booleanValue()
        }

        node.isValueNode -> {
            node
                .asString()
                .trim()
                .lowercase()
                .toBooleanStrictOrNull()
        }

        else -> {
            null
        }
    }
}

/**
 * The text of a scalar node (numbers and booleans as text); `null` for objects and arrays, on which Jackson's `asString`
 * throws. The CLIs' output is not ours, so an unexpected shape must not break a whole listing.
 */
internal fun JsonNode.scalarText(): String? = if (isValueNode) asString() else null

/**
 * The labels in the first of [names]: an object (inspect, Podman) or Docker's `k=v,k=v` text, in which a part without
 * `=` belongs to the value before it (a comma inside a value).
 */
internal fun JsonNode.labels(vararg names: String): Map<String, String> {
    val node = field(*names) ?: return emptyMap()
    if (node.isObject) {
        return node.properties().associate { (key, value) ->
            key to
                (value.scalarText() ?: value.toString())
        }
    }
    val text = node.scalarText()?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val labels = linkedMapOf<String, String>()
    var last: String? = null
    for (part in text.split(',')) {
        val equals = part.indexOf('=')
        if (equals < 0 && last != null) {
            labels[last] = labels[last] + "," + part
        } else if (equals < 0) {
            labels[part] = ""
            last = part
        } else {
            last = part.substring(0, equals)
            labels[last] = part.substring(equals + 1)
        }
    }
    return labels
}

/** The texts in the first of [names]: an array, or Docker's comma-separated text; blanks dropped. */
internal fun JsonNode.texts(vararg names: String): List<String> {
    val node = field(*names) ?: return emptyList()
    val items =
        when {
            node.isArray -> node.values().mapNotNull { it.scalarText() }
            node.isValueNode -> node.asString().split(',')
            else -> emptyList()
        }
    return items.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The texts of a command such as `Config.Cmd`: an array, or a single text. */
internal fun JsonNode.command(vararg names: String): List<String> {
    val node = field(*names) ?: return emptyList()
    return if (node.isArray) {
        node.values().mapNotNull { it.scalarText() }
    } else {
        listOfNotNull(node.scalarText()?.ifBlank { null })
    }
}
