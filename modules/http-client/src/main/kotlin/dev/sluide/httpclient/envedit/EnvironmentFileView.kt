package dev.sluide.httpclient.envedit

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.readTextFile
import dev.sluide.httpclient.HttpClientSession
import dev.sluide.httpclient.problemSummary
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What an environment file's view shows: the JSON source, both side by side, or the form. */
internal enum class EnvMode(
    val label: String,
) {
    SOURCE("Source"),
    SPLIT("Split"),
    VISUAL("Visual"),
}

/**
 * The view of `http-client.env.json` or `http-client.private.env.json` (ADR 0057): the file's [editor] beside a form
 * ([EnvironmentTable]) of both files merged. The opened file changes through its editor; the other one, next to it in
 * the same folder, through its tab ([FileViewHost.editText]), unsaved. Its text comes from that tab or from disk,
 * again whenever the view is shown and after each change the form makes.
 */
internal class EnvironmentFileView(
    file: Path,
    private val editor: CodeEditor,
) : EditableFileView by editor,
    TextEditableView {
    /** The file shown; it changes when the file is [moved]. */
    var file: Path = file.toAbsolutePath().normalize()
        private set

    /** Which of the two files is shown. */
    var side: FileSide = sideOf(this.file) ?: FileSide.PUBLIC
        private set

    /** The other environment file, in the same folder; it may not exist. */
    val otherFile: Path get() = file.resolveSibling(side.other.fileName)

    private val privateFile: Path get() = if (side == FileSide.PRIVATE) file else otherFile

    /** The documents of both texts as last read. */
    var document = EnvDocument(null, null)
        private set

    val form = EnvironmentTable(::apply, ::showFailure)

    val mode = SimpleObjectProperty(EnvMode.SPLIT)
    private val modes = ToggleGroup()
    val modeButtons =
        EnvMode.entries.map { mode ->
            ToggleButton(mode.label).apply {
                toggleGroup = modes
                userData = mode
                styleClass += "env-mode"
                isFocusTraversable = false
            }
        }

    val showValues =
        ToggleButton("Show values").apply {
            styleClass += "env-show-values"
            tooltip = Tooltip("Show the values of the private file")
            selectedProperty().addListener { _, _, shown -> form.showValues = shown }
        }

    val addEnvironment =
        Button("+ Environment").apply {
            styleClass += "env-add-environment"
            setOnAction { form.addEnvironment() }
        }

    /** Problems of the files, what the form could not do, and a private file that git could commit. */
    val warning =
        Label().apply {
            styleClass += "env-warning"
            isWrapText = true
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            isVisible = false
            isManaged = false
        }

    val toolbar =
        HBox(
            addEnvironment,
            showValues,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            *modeButtons.toTypedArray(),
        ).apply {
            styleClass += "env-toolbar"
            alignment = Pos.CENTER_LEFT
        }

    val split = SplitPane(editor.node, form.node)

    private val root =
        BorderPane(split).apply {
            styleClass += "env-view"
            top = VBox(toolbar, warning)
            stylesheets += checkNotNull(EnvironmentFileView::class.java.getResource(CSS)).toExternalForm()
        }

    override val node: Node get() = root

    /** Where the divider was when the view last showed both halves. */
    private var dividerPosition = DIVIDER_POSITION

    /** What the form could not do last, until it does something again. */
    private var failure: String? = null

    /** Why the other file could not be read, which keeps the form from changing the files. */
    private var otherProblem: String? = null

    /** Whether the files could be changed when they were last shown. */
    private var shownEditable: Boolean? = null

    private var disposed = false

    /** Asks again whether git would commit the private file when the window's git status changes. */
    private var gitSubscription: AutoCloseable? = null

    /** Reads the text again once typing pauses; restarted by every change of the text. */
    internal val reparse = PauseTransition(Duration.millis(REPARSE_MILLIS)).apply { setOnFinished { refresh() } }

    /** How often both texts were read into a new [document]; for tests. */
    internal var refreshes = 0
        private set

    init {
        (editor.node as? Region)?.minWidth = 0.0
        // A change listener, not an invalidation listener: the editor's text is computed lazily, and would only tell
        // its next invalidation once read again, so typing on would not restart the pause.
        editor.textProperty.addListener { _, _, _ -> reparse.playFromStart() }
        modes.selectedToggleProperty().subscribe { old, new ->
            // A mode is always selected: clicking the selected button keeps it.
            if (new == null) modes.selectToggle(old) else mode.set(new.userData as EnvMode)
        }
        mode.subscribe { mode ->
            modes.selectToggle(modeButtons.first { it.userData == mode })
            show(mode)
        }
        refresh(force = true)
    }

    override val text: String get() = editor.text

    override val canReplaceText: Boolean get() = editor.canReplaceText

    override fun replaceText(text: String): Boolean = editor.replaceText(text)

    /** Gives the editor the host too, which asks it for diffs when a change marker is clicked. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            gitSubscription?.close()
            gitSubscription = session?.onGitStatusChanged(::updateWarning)
            refresh(force = true)
        }

    /** The window's session, through the [host]; `null` without one (e.g. in tests). */
    private val session: HttpClientSession? get() = host?.windowService(HttpClientSession::class.java)

    /** Reads the other file again, then focuses the source, or the form while it is shown alone. */
    override fun focus() {
        refresh()
        if (mode.get() != EnvMode.VISUAL) return editor.focus()
        if (form.tree.scene != null) {
            form.tree.requestFocus()
        } else {
            Platform.runLater { if (!disposed && form.tree.scene != null) form.tree.requestFocus() }
        }
    }

    override fun focusText() {
        refresh()
        if (mode.get() == EnvMode.VISUAL) form.tree.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to] with the editor, unless [to] is no environment file (another viewer shows it then). */
    override fun moved(to: Path): Boolean {
        val newSide = sideOf(to) ?: return false
        if (!editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        side = newSide
        refresh(force = true)
        return true
    }

    override fun dispose() {
        disposed = true
        reparse.stop()
        gitSubscription?.close()
        editor.dispose()
    }

    private fun show(mode: EnvMode) {
        if (split.items.size == 2) dividerPosition = split.dividerPositions[0]
        when (mode) {
            EnvMode.SOURCE -> {
                split.items.setAll(editor.node)
            }

            EnvMode.SPLIT -> {
                split.items.setAll(editor.node, form.node)
                split.setDividerPositions(dividerPosition)
            }

            EnvMode.VISUAL -> {
                split.items.setAll(form.node)
            }
        }
    }

    /**
     * Reads both texts and shows them in the form, unless nothing changed since the last time (or [force]): the
     * editor's text, and the other file's from its tab or from disk.
     */
    private fun refresh(force: Boolean = false) {
        if (disposed) return
        val own = editor.text
        val other = otherText()
        val editable = editor.canReplaceText && otherProblem == null
        val (publicText, privateText) = if (side == FileSide.PUBLIC) own to other else other to own
        val unchanged = publicText == document.publicText && privateText == document.privateText
        if (unchanged && !force && editable == shownEditable) return
        shownEditable = editable
        refreshes++
        document = EnvDocument(publicText, privateText)
        form.privateFolder = privateFile.parent
        form.show(document, editable && document.table.error == null)
        addEnvironment.isDisable = !form.editable
        updateWarning()
    }

    private fun otherText(): String? {
        otherProblem = null
        val file = otherFile
        host?.currentText(file)?.let { return EnvFileWriter.normalize(it) }
        if (!Files.exists(file)) return null
        return try {
            readTextFile(file)?.let(EnvFileWriter::normalize).also {
                if (it == null) otherProblem = "${file.fileName} is neither UTF-8 nor Windows-1252"
            }
        } catch (e: IOException) {
            otherProblem = "${file.fileName} cannot be read: ${e.message}"
            null
        }
    }

    /**
     * Applies [edit] of the form: first to the other file through its tab (creating it as `{}` if it is missing), then,
     * if that worked, to the editor; then shows the files anew ([EnvFileWriter] decides what is written).
     */
    private fun apply(edit: EnvEdit) {
        val outcome =
            EnvFileWriter(
                side,
                file,
                otherFile,
            ).apply(edit, document, editor.text, editor.canReplaceText, host)
        failure = outcome.failure
        if (failure == null && outcome.ownText != null && !editor.replaceText(outcome.ownText)) {
            failure = "${file.fileName} is read-only"
        }
        // Shows the editor's change at once, not once typing would have paused.
        reparse.stop()
        refresh(force = true)
    }

    private fun showFailure(message: String) {
        failure = message
        updateWarning()
    }

    /** Shows the problems of the files; first of all, what the form could not do and a private file git could commit. */
    private fun updateWarning() {
        val table = document.table
        val committable = privateFile.takeIf { Files.exists(it) && session?.wouldCommit?.invoke(it) == true }
        val readOnly = "${file.fileName} is read-only: the form cannot change it".takeUnless { editor.canReplaceText }
        val problems =
            listOfNotNull(
                failure,
                committable?.let { "${it.fileName} is not ignored by git: its secrets could be committed" },
                otherProblem,
                readOnly,
                table.error,
            ) + table.problems
        val (summary, all) = problemSummary(problems)
        warning.text = summary
        warning.tooltip = all?.let(::Tooltip)
        warning.isVisible = problems.isNotEmpty()
        warning.isManaged = problems.isNotEmpty()
    }

    companion object {
        private const val CSS = "/dev/sluide/httpclient/envedit/environment-editor.css"
        private const val REPARSE_MILLIS = 300.0
        private const val DIVIDER_POSITION = 0.5

        /** The side of an environment file by its name (ignoring case), `null` for other files. */
        fun sideOf(file: Path): FileSide? {
            val name = file.fileName?.toString() ?: return null
            return FileSide.entries.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
        }
    }
}
