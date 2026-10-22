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

/**
 * A viewer for exact file names (case-insensitive), preferred like a code viewer's name match over viewers for the
 * extension (ADR 0029, ADR 0057). [fileNames] only ranks the viewer; [canOpen] must still accept those files.
 */
interface NamedFileViewer : FileViewer {
    val fileNames: Set<String>
}

/**
 * A viewer for files at certain paths, e.g. in `.github/workflows`, preferred over every viewer matching by name or
 * extension (ADR 0074). It takes part only for files at those paths, whatever else it is (an [ExtensionFileViewer],
 * say). [matchesPath] only ranks the viewer; [canOpen] must still accept those files.
 */
interface PathFileViewer : FileViewer {
    /**
     * Whether [file] (absolute, or relative like a diff's name) is at one of this viewer's paths; looks at the
     * location only, never the disk, and not at the type of file: [canOpen] (or [ExtensionFileViewer.fileExtensions],
     * when by name) tells that. See [isInFolders].
     */
    fun matchesPath(file: Path): Boolean
}

/**
 * Whether the folders directly containing [file], the innermost last, end with [folders] exactly (case-sensitive):
 * `isInFolders(file, ".github", "workflows")` for `.github/workflows/ci.yml` and `/repo/.github/workflows/ci.yml`, not
 * for `workflows/ci.yml` or `.github/workflows/sub/ci.yml`. Looks at the path only; false for a bare file name.
 */
fun isInFolders(
    file: Path,
    vararg folders: String,
): Boolean {
    val parents = file.parent?.map { it.toString() } ?: return false
    return parents.size >= folders.size && parents.takeLast(folders.size) == folders.toList()
}

/**
 * A viewer for files with one of the [fileExtensions] (lower case, without the dot). [FileViewers] also picks it by
 * the name alone, without the file, e.g. for a commit's version of a file (ADR 0038). [fileExtensions] only tells
 * the name; [canOpen] must still accept those files.
 */
interface ExtensionFileViewer : FileViewer {
    val fileExtensions: Set<String>
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
     * Gives the focus to the content at once, e.g. the text of a code view, or the source of a preview unless the
     * preview is shown alone; by default to the [node]. Used to give the focus back to the view, e.g. when a diff
     * shown over it is hidden; [focus] is what a view gets when its tab is shown.
     */
    fun focusText() = node.requestFocus()

    /**
     * Gives the focus to the view's main content, at once or as soon as that node is in a scene. Called whenever the
     * view's tab is shown (ADR 0050): the text of a code view, the source of a preview while it shows, the preview
     * while it is shown alone or zooms. Keeps caret and scroll position. By default [focusText], once the [node] is in
     * a scene; the default cannot drop a pending request on [dispose], so views with state that must not take the
     * focus afterwards (code views, previews, images) override it.
     */
    fun focus() = FocusWhenShown.of(node).request(node, ::focusText)

    /**
     * Follows the file to [to], where it was moved (e.g. renamed): from now on the view reads and saves it there, and
     * keeps everything else, including unsaved changes. Returns `false` if the view cannot follow it, as by default;
     * whoever shows the view then opens the file anew.
     */
    fun moved(to: Path): Boolean = false

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

/** A view whose text can be read and replaced, e.g. by a form beside it or another file's view (ADR 0057). */
interface TextEditableView : FileView {
    /** The text with unsaved changes, `\n` line breaks. */
    val text: String

    /** Whether [replaceText] can change the text; `false` e.g. while the view is read-only. */
    val canReplaceText: Boolean get() = true

    /**
     * Replaces the text with [text] (`\n` line breaks) as one undoable change, keeping the caret and scroll position
     * where possible; marks the view dirty if it differs from the saved text. Does nothing if equal. Returns whether
     * the text could be replaced: `true` also if it was equal, `false` if not [canReplaceText]. FX thread.
     */
    fun replaceText(text: String): Boolean
}

/** What a [FileView] can ask of the IDE showing it. */
interface FileViewHost {
    /** Shows [file] in the IDE, e.g. in a tab; `false` if it cannot be shown. */
    fun openFile(file: Path): Boolean

    /** Shows [uri] outside the IDE, e.g. a web page in the system browser. */
    fun openWebPage(uri: URI)

    /** The folder of the project the view belongs to, if any; HTML previews may use its files (ADR 0018). */
    val projectFolder: Path? get() = null

    /**
     * Shows the changes of [file] against its original side by side, at the change at its [line] (0-based), e.g. when
     * a change marker is clicked. Does nothing if the IDE has no diff of the file.
     */
    fun showChanges(
        file: Path,
        line: Int,
    ) {}

    /**
     * The service registered under exactly [type] in the window showing the view (see [WindowServices]), e.g. the HTTP
     * client's session with its cookies and tokens; `null` if there is none (e.g. in tests). Viewers are shared by all
     * windows, so per-window state reaches their views this way (ADR 0026, ADR 0070).
     */
    fun <T : Any> windowService(type: Class<T>): T? = null

    /**
     * The text of [file] as its open tab shows it, unsaved changes included; `null` if it is not open in a
     * [TextEditableView] that [can replace its text][TextEditableView.canReplaceText] (read it from disk then).
     * FX thread.
     */
    fun currentText(file: Path): String? = null

    /**
     * Changes the text of [file] in its tab to `edit(text)` (`\n` line breaks), opening the file in a tab that is not
     * selected if it is not open; the change stays unsaved. `false` if the file cannot be shown in a
     * [TextEditableView] or its view cannot replace its text, e.g. it does not exist or is read-only; [edit] is not
     * called then. FX thread.
     */
    fun editText(
        file: Path,
        edit: (String) -> String,
    ): Boolean = false
}
