package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.color.toRgb8
import dev.sluide.projectdata.ProjectDataStore
import javafx.util.Subscription
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The colors saved per project (ADR 0109): `{"saved": [CSS texts]}` in the top-level field `colors` of the project's
 * data, or of `shared.json` for a window without a folder (ADR 0090, ADR 0101). Application-wide: listeners refresh
 * every window. Call on the FX thread (writes are small and synchronous, as for Todos).
 *
 * Unknown content of `colors` and entries that are not a color text stay as they are on every write. If `colors` or
 * `saved` has the wrong JSON type, nothing is written (it is not ours) and the change is refused.
 */
@Component
class SavedColors(
    private val store: ProjectDataStore,
) {
    private val listeners = CopyOnWriteArrayList<(Path?) -> Unit>()
    private val warned = mutableSetOf<Path?>()

    /** The colors saved for [folder] (`null`: the shared file), in order; entries that cannot be parsed are skipped. */
    fun saved(folder: Path?): List<CssColor> = rawEntries(folder).mapNotNull(::parse)

    /** Whether a color denoting the same 8-bit color as [color] is saved for [folder]. */
    fun isSaved(
        folder: Path?,
        color: CssColor,
    ): Boolean = containsColor(saved(folder), color)

    /** Appends [color] unless the same 8-bit color is saved; `false` if it was not added or the store refused. */
    fun save(
        folder: Path?,
        color: CssColor,
    ): Boolean {
        if (isSaved(folder, color)) return false
        return write(folder) {
            it.add(cssText(color))
            true
        }
    }

    /** Removes the entries denoting the same 8-bit color as [color]; `false` if there were none or it was refused. */
    fun remove(
        folder: Path?,
        color: CssColor,
    ): Boolean {
        if (!isSaved(folder, color)) return false
        return write(folder) { array ->
            val keep = array.filterNot { entry -> parse(entry)?.let { same(it, color) } == true }
            if (keep.size == array.size()) return@write false
            array.removeAll()
            keep.forEach { array.add(it) }
            true
        }
    }

    /**
     * Calls [listener] after every saved change, for every window, on the thread of the change, with the folder that
     * changed (`toAbsolutePath().normalize()`; `null` for the shared file).
     */
    fun onChanged(listener: (Path?) -> Unit): Subscription {
        listeners.add(listener)
        return Subscription { listeners.remove(listener) }
    }

    private fun rawEntries(folder: Path?): List<JsonNode> {
        val root = if (folder == null) store.readShared() else store.read(folder)
        return ((root?.get(FIELD) as? ObjectNode)?.get(SAVED) as? ArrayNode)?.toList().orEmpty()
    }

    private fun parse(entry: JsonNode): CssColor? =
        entry
            .takeIf {
                it.isString
            }?.let { CssColorParser.parse(it.asString()) }

    private fun same(
        a: CssColor,
        b: CssColor,
    ) = containsColor(listOf(a), b)

    /** Applies [change] to the `saved` array in one store write; notifies on success. */
    private fun write(
        folder: Path?,
        change: (ArrayNode) -> Boolean,
    ): Boolean {
        var wrongType = false
        var unchanged = false
        val edit: (ObjectNode) -> Unit = { root ->
            val existing = root.get(FIELD)
            val colors = existing as? ObjectNode ?: if (existing == null || existing.isNull) root.objectNode() else null
            val savedNode = colors?.get(SAVED)
            val array = savedNode as? ArrayNode ?: if (savedNode == null || savedNode.isNull) root.arrayNode() else null
            if (colors == null || array == null) {
                wrongType = true
            } else if (!change(array)) {
                unchanged = true
            } else {
                if (array.size() == 0) colors.remove(SAVED) else colors.set(SAVED, array)
                if (colors.size() == 0) root.remove(FIELD) else root.set(FIELD, colors)
            }
        }
        val written = if (folder == null) store.updateShared(edit) else store.update(folder, edit)
        if (wrongType) warnOnce(folder)
        if (wrongType || unchanged) return false
        if (written) normalized(folder).let { changed -> listeners.forEach { it(changed) } }
        return written
    }

    private fun normalized(folder: Path?) = folder?.toAbsolutePath()?.normalize()

    private fun warnOnce(folder: Path?) {
        if (!warned.add(normalized(folder))) return
        System
            .getLogger(SavedColors::class.java.name)
            .log(
                System.Logger.Level.WARNING,
                "Saved colors of ${folder ?: "the shared file"} not written: field '$FIELD' has an unexpected type",
            )
    }

    companion object {
        private const val FIELD = "colors"
        private const val SAVED = "saved"

        /** Whether [colors] holds a color denoting the same 8-bit color as [color]. */
        internal fun containsColor(
            colors: List<CssColor>,
            color: CssColor,
        ): Boolean = colors.any { it.toRgb8() == color.toRgb8() }
    }
}
