package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.httpclient.HttpClientSession
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import java.nio.file.Path

/**
 * The view of a `.http` file: a toolbar above the file's [editor] and, once a request ran, its response below it.
 * Editing, saving, change markers and changes on disk are the editor's.
 */
internal class HttpFileView(
    file: Path,
    private val editor: CodeEditor,
) : EditableFileView by editor {
    /** The file shown; it changes when the file is [moved]. */
    var file: Path = file.toAbsolutePath().normalize()
        private set

    val toolbar =
        HBox().apply {
            styleClass += "http-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    /** The editor and, once a request ran, the response below it. */
    val split = SplitPane(editor.node).apply { orientation = Orientation.VERTICAL }

    private val root =
        BorderPane(split).apply {
            styleClass += "http-view"
            top = toolbar
            stylesheets += checkNotNull(HttpFileView::class.java.getResource("http-view.css")).toExternalForm()
        }

    override val node: Node get() = root

    /** Gives the editor the host too, which asks it for diffs when a change marker is clicked. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
        }

    /** The window's session, through the [host]; `null` without one (e.g. in tests). */
    val session: HttpClientSession? get() = host?.windowService(HttpClientSession::class.java)

    /**
     * Follows the file to [to] with the editor, if it can: requests then run from there (body files, environment
     * files), and the environment chosen for the file in the window moves along.
     */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        val from = file
        file = to.toAbsolutePath().normalize()
        session?.moveEnvironment(from, file)
        return true
    }
}
