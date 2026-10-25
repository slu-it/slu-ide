package dev.sluide.fileexplorer.tree

import dev.sluide.settings.FontSpec
import javafx.scene.control.TreeItem
import javafx.scene.text.Text
import java.nio.file.Path

/**
 * The width the explorer would like to start with for the top-level [roots] (a collapsed one counts as one entry):
 * enough for the names of the shown entries with their icons and indentation, in [spec], but at least
 * [MIN_START_WIDTH] and at most [MAX_START_WIDTH] times its size.
 */
internal fun startWidth(
    roots: List<TreeItem<Path>>,
    spec: FontSpec,
): Double {
    val text = Text().apply { font = spec.toFont() }
    val max = MAX_START_WIDTH * spec.size
    var width = MIN_START_WIDTH * spec.size
    // Stops once a name needs the largest width, so a folder with thousands of entries is not measured in full.
    for ((item, level) in roots.asSequence().flatMap { shownEntries(it, 0) }) {
        text.text = (item as? FileTreeItem)?.displayName ?: (item.value.fileName ?: item.value).toString()
        width = maxOf(width, (level + ROW_EXTRA) * spec.size + text.layoutBounds.width)
        if (width >= max) return max
    }
    return width
}

/** [item] at [level] and, if it is expanded, its entries below it, in the order shown. */
private fun shownEntries(
    item: TreeItem<Path>,
    level: Int,
): Sequence<Pair<TreeItem<Path>, Int>> =
    sequence {
        yield(item to level)
        if (item.isExpanded) item.children.forEach { yieldAll(shownEntries(it, level + 1)) }
    }

// The start width in multiples of the font size: the bounds, and what a row needs besides its indentation (Modena's
// 1 em per level) and name: the disclosure arrow, the icon, the gaps and padding, a marker bubble or two and the
// vertical scroll bar.
private const val MIN_START_WIDTH = 12.0
private const val MAX_START_WIDTH = 24.0
private const val ROW_EXTRA = 6.0
