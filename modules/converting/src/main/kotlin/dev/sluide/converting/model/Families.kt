package dev.sluide.converting.model

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.TextFormat

/** The format families of the tool, with format lookup, detection and the choice of a target format (ADR 0115). */
internal class Families(
    val all: List<FormatFamily<*>>,
) {
    /** All formats, in family and then format order. */
    val formats: List<TextFormat> = all.flatMap { it.formats }

    fun familyOf(format: TextFormat): FormatFamily<*> =
        requireNotNull(all.firstOrNull { family -> family.formats.any { it.id == format.id } }) {
            "${format.id} is not a format of any family"
        }

    /** The first format whose extensions contain [extension] (with or without the dot), ignoring case. */
    fun formatOf(extension: String): TextFormat? {
        val wanted = extension.removePrefix(".")
        return formats.firstOrNull { format -> format.extensions.any { it.equals(wanted, ignoreCase = true) } }
    }

    /**
     * The surest format of every family that reads [text], except [except]; ties go to [previous] (the side's format
     * so far), then to [lastPinned], then to family and format order. Null for blank text or when none reads it.
     */
    fun detect(
        text: String,
        previous: TextFormat?,
        lastPinned: TextFormat?,
        except: TextFormat? = null,
    ): TextFormat? {
        if (text.isBlank()) return null
        return all
            .flatMap { it.detect(text) }
            .filter { it.format.id != except?.id }
            .minWithOrNull(
                compareByDescending<FormatMatch> { it.score }
                    .thenByDescending { it.format.id == previous?.id }
                    .thenByDescending { it.format.id == lastPinned?.id }
                    .thenBy { order(it.format) },
            )?.format
    }

    /**
     * The target's format for a source in [source]: [current] while it is in the source's family and, unless the user
     * [pinned] it, not the source's format if that [sourceChanged]; else [suggestion]. [current] without a source.
     */
    fun target(
        source: TextFormat?,
        sourceChanged: Boolean,
        current: TextFormat?,
        pinned: Boolean,
        history: List<TextFormat>,
    ): TextFormat? {
        if (source == null) return current
        val keep =
            current != null &&
                familyOf(current) === familyOf(source) &&
                (pinned || !(sourceChanged && current.id == source.id))
        return if (keep) current else suggestion(source, history)
    }

    /**
     * Another format of [source]'s family: the last one used as target ([history], oldest first), else the default
     * partner of [source], else the family's first other format; [source] itself in a family of one.
     */
    fun suggestion(
        source: TextFormat,
        history: List<TextFormat>,
    ): TextFormat {
        val family = familyOf(source).formats
        val others = family.filter { it.id != source.id }
        return history.lastOrNull { used -> others.any { it.id == used.id } }
            ?: DEFAULT_TARGETS[source.id]?.let { id -> others.firstOrNull { it.id == id } }
            ?: others.firstOrNull()
            ?: source
    }

    private fun order(format: TextFormat): Int = formats.indexOfFirst { it.id == format.id }

    companion object {
        /** The usual target of a source format, by format id. */
        val DEFAULT_TARGETS: Map<String, String> =
            mapOf(
                "json" to "yaml",
                "yaml" to "json",
                "toml" to "json",
                "properties" to "yaml",
                "csv" to "json",
                "tsv" to "json",
                "curl" to "http",
                "http" to "curl",
            )
    }
}
