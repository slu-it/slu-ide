package dev.sluide.gradle.view

import dev.sluide.commandruns.CommandRunner
import dev.sluide.commandruns.RunPane
import dev.sluide.commandruns.stopQuestion
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SourceSplit
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.detect.GradleCommand
import dev.sluide.gradle.detect.GradleInstallation
import dev.sluide.gradle.trust.GradleTrustCheck
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.text.Font
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The view of `build.gradle` and `build.gradle.kts` (ADR 0085): the file's [editor] beside the build's tasks above the
 * terminal of their runs. Until Gradle is found (once the [host] gives the project folder and the window's
 * [GradleSession]) it looks like plain source; then the modes Source | Split | Gradle appear, in Split. The tasks
 * ([BuildTasks]) load once per build and window when the folder's trust allows Gradle, and again on Refresh.
 */
internal class GradleFileView(
    file: Path,
    private val editor: CodeEditor,
    private val stopGrace: Duration = 5.seconds,
) : EditableFileView by editor,
    TextEditableView {
    /** The file shown; it changes when the file is [moved]. */
    var file: Path = file.toAbsolutePath().normalize()
        private set

    val tasks = TaskTreePane()
    val runPane = RunPane("Arguments, e.g. --info -x test", "The output of the tasks you run shows here.")

    private val gradlePane =
        SplitPane(tasks.node, runPane.node).apply {
            orientation = Orientation.VERTICAL
            setDividerPositions(0.5)
        }

    val split = SourceSplit(editor.node, gradlePane, "Gradle", "gradle-mode", SplitMode.SOURCE)

    /** The mode buttons; hidden until Gradle is found. */
    val toolbar =
        HBox(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, *split.modeButtons.toTypedArray()).apply {
            styleClass += "gradle-toolbar"
            alignment = Pos.CENTER_LEFT
            isVisible = false
            isManaged = false
        }

    private val root =
        BorderPane(split.node).apply {
            styleClass += "gradle-view"
            top = toolbar
            stylesheets += checkNotNull(GradleFileView::class.java.getResource(CSS)).toExternalForm()
        }

    override val node: Node get() = root

    /**
     * Asked before a run stops the running one: whether to stop it. Shows a dialog owned by the view's window; tests
     * replace it.
     */
    var confirmStop: (running: GradleJob, next: GradleJob) -> Boolean = { running, next ->
        Alert(Alert.AlertType.CONFIRMATION, stopQuestion(running, next), ButtonType.OK, ButtonType.CANCEL)
            .apply {
                initOwner(root.scene?.window)
                title = "Gradle"
                headerText = null
            }.showAndWait()
            .orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    private var session: GradleSession? = null
    var runner: CommandRunner<GradleJob>? = null
        private set
    private var buildTasks: BuildTasks? = null

    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private var disposed = false

    /** The Gradle found for the file; `null` while none is (yet). */
    val installation: GradleInstallation? get() = buildTasks?.installation

    init {
        (editor.node as? Region)?.minWidth = 0.0
        // The editor font the tab gives its content is for the source; the Gradle side uses the UI font.
        Font.getDefault().let { font ->
            val css = "-fx-font-family: \"${font.family}\"; -fx-font-size: ${font.size}px;"
            gradlePane.style = css
            toolbar.style = css
        }
        tasks.onRun = ::runTasks
        tasks.onRefresh = { buildTasks?.detect(reload = true) }
        tasks.onReview = { session?.reviewTrust?.invoke() }
        runPane.rerun.setOnAction { (runner?.last as? GradleJob.Tasks)?.let { runTasks(it.arguments) } }
    }

    override val text: String get() = editor.text

    override val canReplaceText: Boolean get() = editor.canReplaceText

    override fun replaceText(text: String): Boolean = editor.replaceText(text)

    override val activity: ObservableValue<ViewActivity> get() = activityWrapper.readOnlyProperty

    override val closeWarning: String? get() = runner?.closeWarning(file.fileName.toString())

    /** Gives the editor the host too; the first host with a window session starts finding Gradle. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            val session = value?.windowService(GradleSession::class.java)
            if (session != null && this.session == null && !disposed) attach(session)
        }

    private fun attach(session: GradleSession) {
        this.session = session
        val runner =
            CommandRunner<GradleJob>(
                session.runSession,
                runPane,
                { file.parent },
                { running, next -> confirmStop(running, next) },
                rerunnable = { it is GradleJob.Tasks },
                grace = stopGrace,
            )
        this.runner = runner
        activityWrapper.bind(runner.activity)
        val buildTasks = BuildTasks(session, runner, tasks, { file }, { host?.projectFolder }, ::found)
        this.buildTasks = buildTasks
        // Not while the host is being set: the project folder may follow.
        Platform.runLater { buildTasks.detect(reload = false) }
    }

    /** Shows the modes, in Split the first time, once Gradle is found; plain source without one. */
    private fun found(installation: GradleInstallation?) {
        val before = toolbar.isVisible
        toolbar.isVisible = installation != null
        toolbar.isManaged = installation != null
        if (installation == null) {
            split.mode.set(SplitMode.SOURCE)
        } else if (!before) {
            split.mode.set(SplitMode.SPLIT)
        }
    }

    /** Runs the [tasks] (arguments for Gradle: names or paths) with the arguments of the view; the focus stays where it is. */
    private fun runTasks(tasks: List<String>) {
        val runner = runner ?: return
        val installation = installation ?: return
        if (tasks.isEmpty() || session?.projectTrust?.allows(GradleTrustCheck.ID) != true) return
        val arguments = runPane.arguments.text
        runner.start(GradleJob.Tasks(tasks), command = { GradleCommand.run(installation, arguments, tasks) })
    }

    /** Focuses the source, or the tasks while they are shown alone; a failed run no longer marks the tab. */
    override fun focus() {
        runner?.acknowledge()
        if (split.mode.get() != SplitMode.OTHER) return editor.focus()
        if (tasks.tree.scene != null) {
            tasks.tree.requestFocus()
        } else {
            Platform.runLater { if (!disposed && tasks.tree.scene != null) tasks.tree.requestFocus() }
        }
    }

    /** Shows the source beside the tasks if only the tasks are shown, then goes to [line]. */
    override fun showLine(line: Int) {
        if (split.mode.get() == SplitMode.OTHER) split.mode.set(SplitMode.SPLIT)
        editor.showLine(line)
    }

    override fun focusText() {
        if (split.mode.get() == SplitMode.OTHER) tasks.tree.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to] if it has the same name (the same viewer shows it), and finds its Gradle again. */
    override fun moved(to: Path): Boolean {
        val name = to.fileName?.toString() ?: return false
        if (!name.equals(file.fileName.toString(), ignoreCase = true) || !editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        buildTasks?.moved()
        return true
    }

    override fun dispose() {
        disposed = true
        runner?.dispose()
        buildTasks?.close()
        activityWrapper.unbind()
        editor.dispose()
    }

    private companion object {
        const val CSS = "/dev/sluide/gradle/view/gradle-view.css"
    }
}
