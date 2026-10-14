package dev.sluide.fileviewers

import javafx.beans.value.ObservableBooleanValue
import javafx.scene.Node
import java.io.IOException
import java.net.URI
import java.nio.file.Path

/**
 * Shows the content of a file, read-only or editable ([EditableFileView]). Every Spring bean implementing this
 * interface is asked by [FileViewers] whether it can show a file; [DefaultTextFileViewer] is always asked last.
 */
interface FileViewer {
    /** Whether this viewer can show [file]. Must be cheap and must not throw. */
    fun canOpen(file: Path): Boolean

    /** Creates the view for [file], which [canOpen] accepted. */
    @Throws(IOException::class)
    fun open(file: Path): FileView
}

/** A file shown by a [FileViewer]. */
interface FileView {
    /** The JavaFX node displaying the file. */
    val node: Node

    /**
     * Reads the file again and shows the new content, keeping scroll position and caret as far as possible.
     * Does nothing if the content did not change; on an [IOException] the old content stays. Never throws away
     * unsaved changes of an [EditableFileView]: with such changes, the view lets the user choose between them and the
     * new content.
     */
    @Throws(IOException::class)
    fun reload()

    /**
     * Releases what the view holds besides its node, e.g. stops the page of a `WebView` with its scripts, timers and
     * requests. Called once by whoever shows the view when it is no longer shown (its tab closed, or another view
     * took its place); the view is not used afterwards. Views that hold nothing do nothing.
     */
    fun dispose() {}

    /**
     * Lets the view open other files and web pages, e.g. when a link in it is clicked. Set by whoever shows the view;
     * views that never open anything ignore it.
     */
    var host: FileViewHost?
        get() = null
        set(_) {}
}

/** A [FileView] in which the file can be edited and saved. */
interface EditableFileView : FileView {
    /** Whether the view has changes that are not saved. */
    val dirty: ObservableBooleanValue

    /** Writes the edited text to the file, in place, with the file's line endings; then the view is not [dirty]. */
    @Throws(IOException::class)
    fun save()

    /**
     * Marks the lines that differ from the original of [diff] (e.g. the last commit) beside the text, for the file as
     * it is on disk; `null` removes the markers. Views without such markers ignore it.
     */
    fun showChanges(diff: TextDiff?) {}
}

/** What a [FileView] can ask of the IDE showing it. */
interface FileViewHost {
    /** Shows [file] in the IDE, e.g. in a tab; `false` if it cannot be shown. */
    fun openFile(file: Path): Boolean

    /** Shows [uri] outside the IDE, e.g. a web page in the system browser. */
    fun openWebPage(uri: URI)

    /** The folder of the project the view belongs to, if any; HTML previews may use its files (ADR 0018). */
    val projectFolder: Path? get() = null
}
