package dev.sluide.findinfiles

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.IndexRange
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import java.nio.file.Path

/** An [ExtensibleCodeEditor] that keeps the context menu items function it is given; [popupOwner] is its focus node. */
internal class FakeEditor(
    override val popupOwner: Node? = null,
    private val selected: () -> IndexRange = { IndexRange(0, 0) },
    private val content: () -> String = { "" },
) : ExtensibleCodeEditor {
    lateinit var items: (EditorMenuTarget) -> List<MenuItem>

    override val file: ObservableValue<Path> = SimpleObjectProperty(Path.of("/proj/a.txt"))
    override val lineCount get() = content().split("\n").size
    override val shownLines: IntRange = IntRange.EMPTY
    override val selection: IndexRange get() = selected()
    override val text: String get() = content()

    override fun lineText(line: Int) = content().split("\n")[line]

    override fun onShownLinesChanged(action: () -> Unit) = Subscription {}

    override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
        this.items = items
    }

    override fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) = Unit

    override fun refreshGutter() = Unit

    /** The menu items for a menu opened over [selectedText]. */
    fun menu(selectedText: String) = items(EditorMenuTarget(0, 0, selectedText))
}
