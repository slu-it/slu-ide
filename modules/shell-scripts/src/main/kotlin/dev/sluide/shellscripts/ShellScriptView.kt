package dev.sluide.shellscripts

import dev.sluide.commandruns.CommandRunSession
import dev.sluide.commandruns.CommandRunner
import dev.sluide.commandruns.RunPane
import dev.sluide.commandruns.stopQuestion
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.RunnableFileView
import dev.sluide.fileviewers.SourceSplit
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.TextInputControl
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.text.Font
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignP
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The view of a shell script (ADR 0089): its [editor], Run in the toolbar and the modes Source | Split | Output (opens
 * in Split), where Output is the [runPane] with the arguments and the terminal of the runs. Run (the button, Shortcut+
 * Enter in the source, Enter in the arguments, Rerun, or [run] from outside) shows the output beside the source and
 * runs the script through a [ScriptRunFlow], once the [host] gives the window's [CommandRunSession].
 */
internal class ShellScriptView(
    path: Path,
    private val editor: CodeEditor,
    private val stopGrace: Duration = 5.seconds,
) : EditableFileView by editor,
    TextEditableView,
    RunnableFileView {
    /** The script; it changes when the file is [moved]. */
    var file: Path = path.toAbsolutePath().normalize()
        private set

    val runPane = RunPane("e.g. --verbose input.txt", "The output of the script shows here.")

    val split = SourceSplit(editor.node, runPane.node, "Output", "script-mode", SplitMode.SPLIT)

    /** Runs the script; disabled outside an IDE window (no [CommandRunSession]). */
    val runButton =
        Button("Run", FontIcon(MaterialDesignP.PLAY)).apply {
            styleClass += "script-run"
            minWidth = Region.USE_PREF_SIZE
            setOnAction { run() }
        }

    val toolbar =
        HBox(runButton, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, *split.modeButtons.toTypedArray())
            .apply {
                styleClass += "script-toolbar"
                alignment = Pos.CENTER_LEFT
            }

    private val root =
        BorderPane(split.node).apply {
            styleClass += "script-view"
            top = toolbar
            stylesheets += checkNotNull(ShellScriptView::class.java.getResource(CSS)).toExternalForm()
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (RUN_KEY.match(event) && inSource(event.target)) {
                    event.consume()
                    run()
                }
            }
        }

    override val node: Node get() = root

    /** Asked before a run stops the running one: whether to stop it. A dialog owned by the view's window; tests replace it. */
    var confirmStop: (running: ScriptJob, next: ScriptJob) -> Boolean = { running, next ->
        alert(stopQuestion(running, next), ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK
    }

    /** Asked before a run while the source has unsaved changes. A dialog owned by the view's window; tests replace it. */
    var askUnsaved: (question: String) -> UnsavedChoice = { question ->
        when (alert(question, SAVE_AND_RUN, RUN_SAVED, ButtonType.CANCEL)) {
            SAVE_AND_RUN -> UnsavedChoice.SAVE_AND_RUN
            RUN_SAVED -> UnsavedChoice.RUN_SAVED
            else -> UnsavedChoice.CANCEL
        }
    }

    /** Asked before a run of a script that is not executable. A dialog owned by the view's window; tests replace it. */
    var askMakeExecutable: (question: String) -> Boolean = { question ->
        alert(question, ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK
    }

    /** Runs the script, one run at a time; `null` until the host gives the window's [CommandRunSession]. */
    var runner: CommandRunner<ScriptJob>? = null
        private set

    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private var disposed = false

    private val flow =
        ScriptRunFlow(
            object : ScriptRunHost {
                override val file: Path get() = this@ShellScriptView.file

                override val isDirty: Boolean get() = editor.dirty.get()

                override fun askUnsaved(question: String) = this@ShellScriptView.askUnsaved(question)

                override fun save() = host?.save(file) ?: false

                override fun isExecutable() = Files.isExecutable(file)

                override fun askMakeExecutable(question: String) = this@ShellScriptView.askMakeExecutable(question)

                override fun makeExecutable() = addOwnerExecute(file)

                override fun replaceRunning(job: ScriptJob) = runner?.replaceRunning(job) ?: false

                override fun start(
                    job: ScriptJob,
                    command: String,
                ) {
                    showOutput()
                    runner?.start(job, command = { command })
                }

                override fun showProblem(text: String) {
                    showOutput()
                    runner?.showProblem(text)
                }
            },
        )

    init {
        (editor.node as? Region)?.minWidth = 0.0
        // The editor font the tab gives its content is for the source; the toolbar and the run pane use the UI font.
        Font.getDefault().let { font ->
            val css = "-fx-font-family: \"${font.family}\"; -fx-font-size: ${font.size}px;"
            runPane.node.style = css
            toolbar.style = css
        }
        runPane.arguments.setOnAction { run() }
        runPane.rerun.setOnAction { run() }
        updateRunnable()
    }

    override val text: String get() = editor.text

    override val canReplaceText: Boolean get() = editor.canReplaceText

    override fun replaceText(text: String): Boolean = editor.replaceText(text)

    override val activity: ObservableValue<ViewActivity> get() = activityWrapper.readOnlyProperty

    override val closeWarning: String? get() = runner?.closeWarning(file.fileName.toString())

    /** Gives the editor the host too; the first host with the window's [CommandRunSession] lets the script run. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            val session = value?.windowService(CommandRunSession::class.java)
            if (session != null && runner == null && !disposed) attach(session)
        }

    private fun attach(session: CommandRunSession) {
        val runner =
            CommandRunner<ScriptJob>(
                session,
                runPane,
                { checkNotNull(file.parent) },
                { running, next -> confirmStop(running, next) },
                grace = stopGrace,
            )
        this.runner = runner
        activityWrapper.bind(runner.activity)
        updateRunnable()
    }

    private fun updateRunnable() {
        val runnable = runner != null
        runButton.isDisable = !runnable
        runButton.tooltip = Tooltip(if (runnable) "Run the script (${RUN_KEY.displayText})" else NOT_RUNNABLE)
    }

    /**
     * Runs the script with the arguments of the view, asking first what needs asking ([ScriptRunFlow]); shows the
     * output beside the source. Does nothing outside an IDE window.
     */
    override fun run() {
        if (runner == null || disposed) return
        flow.run(runPane.arguments.text)
    }

    /** Shows the output beside the source if only the source shows. */
    private fun showOutput() {
        if (split.mode.get() == SplitMode.SOURCE) split.mode.set(SplitMode.SPLIT)
    }

    /** Whether [target] is the source's text, not e.g. a field of its find bar. */
    private fun inSource(target: Any?): Boolean {
        if (target !is Node || target is TextInputControl) return false
        return generateSequence(target) { it.parent }.any { it === editor.node }
    }

    private fun alert(
        question: String,
        vararg buttons: ButtonType,
    ): ButtonType =
        Alert(Alert.AlertType.CONFIRMATION, question, *buttons)
            .apply {
                initOwner(root.scene?.window)
                title = "Run Script"
                headerText = null
            }.showAndWait()
            .orElse(ButtonType.CANCEL)

    /** Focuses the source, or the arguments while the output is shown alone; a failed run no longer marks the tab. */
    override fun focus() {
        runner?.acknowledge()
        if (split.mode.get() != SplitMode.OTHER) return editor.focus()
        val arguments = runPane.arguments
        if (arguments.scene != null) {
            arguments.requestFocus()
        } else {
            Platform.runLater { if (!disposed && arguments.scene != null) arguments.requestFocus() }
        }
    }

    /** Shows the source beside the output if only the output is shown, then goes to [line]. */
    override fun showLine(line: Int) {
        if (split.mode.get() == SplitMode.OTHER) split.mode.set(SplitMode.SPLIT)
        editor.showLine(line)
    }

    override fun focusText() {
        if (split.mode.get() == SplitMode.OTHER) runPane.arguments.requestFocus() else editor.focusText()
    }

    /**
     * Follows the script to [to], keeping its run, arguments and unsaved changes. The editor area asks only when this
     * viewer also shows the new path, or the tab has unsaved changes it must not lose.
     */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        return true
    }

    override fun dispose() {
        disposed = true
        runner?.dispose()
        activityWrapper.unbind()
        editor.dispose()
    }

    private companion object {
        const val CSS = "/dev/sluide/shellscripts/script-view.css"
        const val NOT_RUNNABLE = "Scripts run in an IDE window"
        val RUN_KEY = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
        val SAVE_AND_RUN = ButtonType("Save and Run", ButtonBar.ButtonData.OK_DONE)
        val RUN_SAVED = ButtonType("Run Saved Version", ButtonBar.ButtonData.OTHER)
    }
}
