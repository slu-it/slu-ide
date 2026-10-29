package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.GutterColumns
import dev.sluide.fileviewers.onShownParagraphsChanged
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import java.nio.file.Path

/** [ExtensibleCodeEditor] over a [CodeView]'s [area] showing [initialFile]; its gutter is the view's. FX thread. */
internal class ExtensibleCodeView(
    gutter: GutterColumns,
    private val area: CodeArea,
    initialFile: Path,
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
}
