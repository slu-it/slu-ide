package dev.sluide.docker.dockerfile

import dev.sluide.commandruns.CommandRunSession
import dev.sluide.commandruns.CommandRunner
import dev.sluide.commandruns.RunState
import dev.sluide.commandruns.StartResult
import dev.sluide.commandruns.stopQuestion
import dev.sluide.docker.Docker
import dev.sluide.docker.DockerSession
import dev.sluide.docker.compose.FxScheduler
import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.RunnableFileView
import dev.sluide.fileviewers.SourceSplit
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.fileviewers.WrappingToolbar
import javafx.animation.PauseTransition
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.text.Font
import javafx.stage.DirectoryChooser
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The view of a Dockerfile: its [editor] and the modes Source | Split | Build (opens in Split), where Build is the
 * [pane] that builds the image and offers to run it. Once the [host] gives the window's [DockerSession] and
 * [CommandRunSession], a [BuildPaneModel] keeps the options and runs the build in the pane's Output; outside a window
 * the pane stays disabled. The stages and ARGs follow the editor's text after a [reparsePause]. Explorer Run ([run])
 * is Build.
 */
internal class DockerfileView(
    path: Path,
    private val editor: CodeEditor,
    private val store: DockerBuildStore,
    /** The executable of the Docker settings, for the command line while the session has not resolved one. */
    private val executableSetting: () -> String,
    private val stopGrace: Duration = 5.seconds,
    /** The pause after typing before the stages and ARGs are read again. */
    private val reparsePause: Duration = 300.milliseconds,
    /** The pause after a change of the options before they are stored. */
    private val storeDebounce: Duration = 300.milliseconds,
) : EditableFileView by editor,
    TextEditableView,
    RunnableFileView {
    /** The Dockerfile; it changes when the file is [moved]. */
    var file: Path = path.toAbsolutePath().normalize()
        private set

    val pane = BuildPane()

    val split = SourceSplit(editor.node, pane.node, "Build", "dockerfile-mode", SplitMode.SPLIT)

    val toolbar =
        WrappingToolbar().apply {
            styleClass += "dockerfile-view-toolbar"
            leading.setAll(editor.softWrapButton)
            trailing.setAll(split.modeButtons)
        }

    private val root =
        BorderPane(split.node).apply {
            styleClass += "dockerfile-view"
            top = toolbar
            stylesheets += checkNotNull(DockerfileView::class.java.getResource(CSS)).toExternalForm()
        }

    override val node: Node get() = root

    /** Asked before a build stops the running one. A dialog owned by the view's window; tests replace it. */
    var confirmStop: (running: BuildJob, next: BuildJob) -> Boolean = { running, next ->
        alert(stopQuestion(running, next), ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK
    }

    /** Asked before a build while the source has unsaved changes. A dialog owned by the view's window; tests replace it. */
    var askUnsaved: (question: String) -> BuildUnsavedChoice = { question ->
        when (alert(question, SAVE_AND_BUILD, BUILD_SAVED, ButtonType.CANCEL)) {
            SAVE_AND_BUILD -> BuildUnsavedChoice.SAVE_AND_BUILD
            BUILD_SAVED -> BuildUnsavedChoice.BUILD_SAVED
            else -> BuildUnsavedChoice.CANCEL
        }
    }

    /**
     * Opens the Run dialog for an image (Run…): the window's Docker tool. Tests replace it, as the tool is a final class
     * of the window.
     */
    var runImage: (reference: String) -> Unit = { reference ->
        host?.windowService(Docker::class.java)?.runImage(reference)
    }

    /** The pane's state; `null` until the host gives the window's sessions. */
    var model: BuildPaneModel? = null
        private set

    /** Runs the builds in the pane's Output; `null` until the [model] is. */
    var runner: CommandRunner<BuildJob>? = null
        private set

    /** The stages and ARGs of the source, as last parsed. */
    private var info = parseDockerfile(editor.text)

    private val reparse =
        PauseTransition(javafx.util.Duration.millis(reparsePause.inWholeMilliseconds.toDouble())).apply {
            setOnFinished { parse() }
        }
    private val textSubscription = editor.onTextChanged { reparse.playFromStart() }

    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private var disposed = false

    init {
        // Soft Wrap (ADR 0130) shows with the source side only.
        editor.softWrapButton.visibleProperty().bind(split.mode.map { it != SplitMode.OTHER })
        editor.softWrapButton.managedProperty().bind(editor.softWrapButton.visibleProperty())
        (editor.node as? Region)?.minWidth = 0.0
        // The editor font the tab gives its content is for the source; the toolbar and the pane use the UI font.
        Font.getDefault().let { font ->
            val css = "-fx-font-family: \"${font.family}\"; -fx-font-size: ${font.size}px;"
            pane.node.style = css
            toolbar.style = css
        }
        pane.form.chooseFolder = { initial ->
            DirectoryChooser()
                .apply {
                    title = "Build Context"
                    initialDirectory = initial.toFile().takeIf { it.isDirectory }
                }.showDialog(root.scene?.window)
                ?.toPath()
        }
        // Through Build again, with its questions (BuildPaneModel.rerun).
        pane.runPane.rerun.setOnAction { model?.rerun() }
    }

    override val text: String get() = editor.text

    override val canReplaceText: Boolean get() = editor.canReplaceText

    override fun replaceText(text: String): Boolean = editor.replaceText(text)

    override val activity: ObservableValue<ViewActivity> get() = activityWrapper.readOnlyProperty

    override val closeWarning: String? get() = runner?.closeWarning(file.fileName.toString())

    /** Gives the editor the host too; the first host with the window's sessions connects the pane. */
    override var host: FileViewHost?
        get() = editor.host
        set(value) {
            editor.host = value
            val docker = value?.windowService(DockerSession::class.java)
            val runs = value?.windowService(CommandRunSession::class.java)
            if (docker != null && runs != null && model == null && !disposed) attach(docker, runs)
        }

    private fun attach(
        session: DockerSession,
        runs: CommandRunSession,
    ) {
        val runner =
            CommandRunner<BuildJob>(
                runs,
                pane.runPane,
                { checkNotNull(file.parent) },
                { running, next -> confirmStop(running, next) },
                grace = stopGrace,
            )
        this.runner = runner
        activityWrapper.bind(runner.activity)
        val model =
            BuildPaneModel(
                session = session,
                store = store,
                file = { file },
                projectFolder = { host?.projectFolder },
                executable = { session.docker?.executable?.toString() ?: executableSetting() },
                host = ViewHost(),
                scheduler = FxScheduler,
                debounce = storeDebounce,
            )
        model.info.set(info)
        this.model = model
        pane.bind(model) { checkNotNull(file.parent) }
    }

    /** Build (explorer Run): shows the pane beside the source first. Does nothing outside an IDE window. */
    override fun run() {
        val model = model ?: return
        if (disposed) return
        showPane()
        model.build()
    }

    private fun start(
        job: BuildJob,
        command: () -> String,
        environment: Map<String, String>,
        ended: (Boolean) -> Unit,
    ): Boolean {
        val runner = runner ?: return false
        showPane()
        val result = runner.start(job, command, { state -> ended(state is RunState.Finished) }, null, environment)
        return result == StartResult.STARTED
    }

    /** Shows the pane beside the source if only the source shows. */
    private fun showPane() {
        if (split.mode.get() == SplitMode.SOURCE) split.mode.set(SplitMode.SPLIT)
    }

    private fun parse() {
        val parsed = parseDockerfile(editor.text)
        if (parsed == info) return
        info = parsed
        model?.info?.set(parsed)
    }

    private fun alert(
        question: String,
        vararg buttons: ButtonType,
    ): ButtonType =
        Alert(Alert.AlertType.CONFIRMATION, question, *buttons)
            .apply {
                initOwner(root.scene?.window)
                title = "Build"
                headerText = null
            }.showAndWait()
            .orElse(ButtonType.CANCEL)

    /** Focuses the source, or the pane's Build while the pane is shown alone; a failed build no longer marks the tab. */
    override fun focus() {
        runner?.acknowledge()
        if (split.mode.get() != SplitMode.OTHER) editor.focus() else pane.build.requestFocus()
    }

    /** Shows the source beside the pane if only the pane is shown, then goes to [line]. */
    override fun showLine(line: Int) = showPosition(line, 0)

    /** Shows the source beside the pane if only the pane is shown, then goes to [column] of [line]. */
    override fun showPosition(
        line: Int,
        column: Int,
    ) {
        if (split.mode.get() == SplitMode.OTHER) split.mode.set(SplitMode.SPLIT)
        editor.showPosition(line, column)
    }

    override fun focusText() {
        if (split.mode.get() == SplitMode.OTHER) pane.build.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to], keeping its build, options and unsaved changes; the options are stored for [to]. */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        pane.form.showFolder(checkNotNull(file.parent))
        model?.moved()
        return true
    }

    override fun dispose() {
        disposed = true
        model?.close()
        runner?.dispose()
        reparse.stop()
        textSubscription.unsubscribe()
        activityWrapper.unbind()
        editor.dispose()
    }

    /** The model's way to the editor, the dialogs, the runner and the window's Docker tool. */
    private inner class ViewHost : BuildHost {
        override val isDirty: Boolean get() = editor.dirty.get()

        override val text: String get() = editor.text

        override fun askUnsaved(question: String) = this@DockerfileView.askUnsaved(question)

        override fun save() = host?.save(file) ?: false

        override fun start(
            job: BuildJob,
            command: () -> String,
            environment: Map<String, String>,
            ended: (succeeded: Boolean) -> Unit,
        ) = this@DockerfileView.start(job, command, environment, ended)

        override fun runImage(reference: String) = this@DockerfileView.runImage(reference)

        override fun showProblem(message: String) {
            runner?.showProblem(message)
        }
    }

    private companion object {
        const val CSS = "/dev/sluide/docker/dockerfile/dockerfile-view.css"
        val SAVE_AND_BUILD = ButtonType("Save and Build", ButtonBar.ButtonData.OK_DONE)
        val BUILD_SAVED = ButtonType("Build Saved Version", ButtonBar.ButtonData.OTHER)
    }
}
