package dev.sluide.ide.compare

import dev.sluide.editorarea.EditorTab
import dev.sluide.fileviewers.DiffFileView
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.layout.StackPane

/**
 * Two files side by side in a read-only tab (ADR 0113): the [view] of [comparison]. The tab is a snapshot, nothing
 * watches the files; [replace] shows another state of the comparison, e.g. the sides swapped or the files read again.
 * The tooltip names both files in the order they were first shown, without implying sides, as [EditorTab.tooltip] is
 * not observable.
 */
internal class FileComparisonTab(
    comparison: FileComparison,
    private var view: DiffFileView,
) : EditorTab {
    var comparison = comparison
        private set

    private val titleProperty = ReadOnlyStringWrapper(comparison.title())

    override val title: ObservableValue<String> = titleProperty.readOnlyProperty

    override val tooltip = comparison.tooltip()

    override val usesEditorFont = true
    override val closableByShortcut = true

    private val holder = StackPane(view.node)

    override val content: Node get() = holder

    /** Shows [other] in [view] instead of the view shown so far, which is disposed. */
    fun replace(
        other: FileComparison,
        view: DiffFileView,
    ) {
        this.view.dispose()
        this.view = view
        comparison = other
        titleProperty.set(other.title())
        holder.children.setAll(view.node)
    }

    override fun dispose() = view.dispose()
}
