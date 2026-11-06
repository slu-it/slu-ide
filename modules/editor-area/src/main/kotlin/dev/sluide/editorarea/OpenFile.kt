package dev.sluide.editorarea

import dev.sluide.fileviewers.DiffFileView
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.TextDiff
import dev.sluide.fileviewers.ViewActivity
import javafx.beans.binding.Bindings
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.layout.StackPane
import java.nio.file.Path

/**
 * A file shown in a tab: its [editor], the view it was opened with, which stays until the tab closes, and, on demand,
 * a [diffView] over it. The editor keeps its text, caret and undo history while the diff is shown. A diff view is
 * disposed when it is hidden or replaced, all views when the tab closes. While the editor has unsaved changes
 * ([dirty]), the title starts with a dot. The [path] changes when the editor follows its file to a new one.
 */
internal class OpenFile(
    path: Path,
    val editor: FileView,
    /** Whether the file is shown as text, which can have change markers and a diff. */
    val showsDiffs: Boolean = false,
    /** The viewer that opened the [editor], if known. */
    val viewer: FileViewer? = null,
) : EditorTab {
    /** The file's path; title and tooltip follow it. */
    val pathProperty = SimpleObjectProperty(path)

    var path: Path
        get() = pathProperty.get()
        set(value) = pathProperty.set(value)

    val name: String get() = (path.fileName ?: path).toString()

    /** Whether the editor is an [EditableFileView] with unsaved changes, also while the diff is shown. */
    val dirty: ObservableBooleanValue = (editor as? EditableFileView)?.dirty ?: NEVER_DIRTY

    /** The current value of [dirty]. */
    val isDirty: Boolean get() = dirty.get()

    override val title: ObservableValue<String> =
        Bindings.createStringBinding({ if (isDirty) "● $name" else name }, dirty, pathProperty)

    override val tooltip: String get() = path.toString()

    /** The [editor]'s warning, e.g. while it runs something; the window asks with it too. */
    override val closeWarning: String? get() = editor.closeWarning

    /** What the [editor] is doing, shown as a marker on the tab header. */
    override val activity: ObservableValue<ViewActivity> get() = editor.activity

    override val usesEditorFont = true

    private val editorLayer = StackPane(editor.node)

    override val content = StackPane(editorLayer)

    /** The diff of the file against its original as last answered, `null` if it has none. */
    val diff = SimpleObjectProperty<TextDiff?>()

    /** Counts the requests for [diff]; only the answer to the newest is taken (ADR 0062). */
    var diffRequests = 0L

    private val diffViewProperty = SimpleObjectProperty<DiffFileView?>()

    /** The diff shown over the editor, which is hidden meanwhile; `null` while the editor is shown. */
    var diffView: DiffFileView?
        get() = diffViewProperty.get()
        set(value) {
            val old = diffViewProperty.get()
            if (old === value) return
            diffViewProperty.set(value)
            old?.let { content.children -= it.node }
            value?.let { content.children += it.node }
            editorLayer.isVisible = value == null
            old?.dispose()
        }

    /** What had the focus in the editor when the diff was shown, to get it back afterwards. */
    var focusInEditor: Node? = null

    /** Whether the file has a [diff] to show, or shows it. */
    val hasDiff: ObservableBooleanValue = diff.isNotNull.or(diffViewProperty.isNotNull)

    /** The view shown: the diff if it is, otherwise the editor. */
    val view: FileView get() = diffView ?: editor

    override fun dispose() {
        diffView?.dispose()
        editor.dispose()
    }
}

/** The [OpenFile.dirty] state of views that cannot be edited. */
private val NEVER_DIRTY: ObservableBooleanValue = ReadOnlyBooleanWrapper(false).readOnlyProperty
