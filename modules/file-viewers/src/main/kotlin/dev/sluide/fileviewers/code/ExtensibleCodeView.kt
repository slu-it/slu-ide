package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.fileviewers.onShownParagraphsChanged
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.control.IndexRange
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import java.nio.file.Path

/**
 * [ExtensibleCodeEditor] over a [CodeView]'s [area] showing [initialFile]; its gutter is the view's, what extensions
 * add beside it goes to [additions]. [plainText]: the view has no highlighter. FX thread.
 */
internal class ExtensibleCodeView(
    gutter: GutterColumns,
    private val area: CodeArea,
    initialFile: Path,
    private val additions: CodeViewAdditions,
    override val plainText: Boolean,
) : ExtensibleCodeEditor,
    GutterColumns by gutter {
    private val fileWrapper = ReadOnlyObjectWrapper(initialFile)
    private val menuItems = mutableListOf<(EditorMenuTarget) -> List<MenuItem>>()

    override val file: ObservableValue<Path> = fileWrapper.readOnlyProperty

    /** The view shows [to] now. */
    fun moved(to: Path) = fileWrapper.set(to)

    /** The providers of the items of the context menu, in the order they were added. */
    val contextMenuItems: List<(EditorMenuTarget) -> List<MenuItem>> get() = menuItems.toList()

    override val lineCount: Int get() = area.paragraphs.size

    override fun lineText(line: Int): String = area.getText(line)

    override val shownLines: IntRange
        get() =
            if (area.visibleParagraphs.isEmpty()) {
                IntRange.EMPTY
            } else {
                area.firstVisibleParToAllParIndex()..area.lastVisibleParToAllParIndex()
            }

    override fun onShownLinesChanged(action: () -> Unit): Subscription = area.onShownParagraphsChanged(action)

    override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
        menuItems += items
    }

    override val text: String get() = area.text

    override val caretOffset: Int get() = area.caretPosition

    override val selection: IndexRange get() = area.selection

    override fun onTextChanged(action: () -> Unit): Subscription {
        val changes = area.plainTextChanges().subscribe { action() }
        return Subscription { changes.unsubscribe() }
    }

    override fun addToolbarItems(nodes: List<Node>): AutoCloseable = additions.toolbar.add(nodes)

    override fun addBanner(node: Node): AutoCloseable = additions.addBanner(node)

    override fun lock(): AutoCloseable = additions.locks.lock()

    override fun overlay(
        layer: String,
        ranges: List<StyledRange>,
    ) = additions.layers.set(layer, ranges)

    override fun reveal(
        start: Int,
        end: Int,
    ) = area.revealRange(start, end)

    override fun addStylesheet(url: String): AutoCloseable {
        area.stylesheets += url
        return AutoCloseable { area.stylesheets -= url }
    }

    override fun <T : Event> addEventFilter(
        type: EventType<T>,
        filter: EventHandler<in T>,
    ): Subscription {
        area.addEventFilter(type, filter)
        return Subscription { area.removeEventFilter(type, filter) }
    }

    override fun addActivity(activity: ObservableValue<ViewActivity>): Subscription = additions.activities.add(activity)
}
