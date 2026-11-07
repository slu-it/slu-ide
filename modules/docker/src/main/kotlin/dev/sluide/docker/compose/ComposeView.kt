package dev.sluide.docker.compose

import dev.sluide.commandruns.CommandRunSession
import dev.sluide.commandruns.CommandRunner
import dev.sluide.commandruns.StartResult
import dev.sluide.commandruns.stopQuestion
import dev.sluide.docker.Docker
import dev.sluide.docker.DockerSession
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
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.shape.Polygon
import javafx.scene.text.Font
import javafx.stage.FileChooser
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The view of a compose file (ADR 0119): its [editor] with a ▶ in the gutter on each service, and the modes Source |
 * Split | Compose (opens in Split), where Compose is the [pane] that runs and manages the project. Once the [host]
 * gives the window's [DockerSession] and [CommandRunSession], a [ComposePaneModel] reads the file's configuration and
 * runs its commands in the pane's Output; outside a window the pane stays disabled. Explorer Run ([run]) is Up.
 */
internal class ComposeView(
    path: Path,
    private val editor: CodeEditor,
    private val store: ComposeStore,
    /** The executable of the Docker settings, for the command lines while the session has not resolved one. */
    private val executableSetting: () -> String,
    private val stopGrace: Duration = 5.seconds,
    /** The pause after typing before the gutter's ▶ move to the new service lines. */
    private val reparsePause: Duration = 300.milliseconds,
    /** The pause after a change of the options before the configuration is read again. */
    private val reloadDebounce: Duration = 300.milliseconds,
) : EditableFileView by editor,
    TextEditableView,
    RunnableFileView {
    /** The compose file; it changes when the file is [moved]. */
    var file: Path = path.toAbsolutePath().normalize()
        private set

    val pane = ComposePane()

    val split = SourceSplit(editor.node, pane.node, "Compose", "compose-mode", SplitMode.SPLIT)

    val toolbar =
        WrappingToolbar().apply {
            styleClass += "compose-view-toolbar"
            leading.setAll(editor.softWrapButton)
            trailing.setAll(split.modeButtons)
        }

    private val root =
        BorderPane(split.node).apply {
            styleClass += "compose-view"
            top = toolbar
            stylesheets += checkNotNull(ComposeView::class.java.getResource(CSS)).toExternalForm()
        }

    override val node: Node get() = root

    /** Asked before a run stops the running one. A dialog owned by the view's window; tests replace it. */
    var confirmStop: (running: ComposeJob, next: ComposeJob) -> Boolean = { running, next ->
        alert(stopQuestion(running, next), ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK
    }

    /** Asked before an Up while the source has unsaved changes. A dialog owned by the view's window; tests replace it. */
    var askUnsaved: (question: String) -> UnsavedChoice = { question ->
        when (alert(question, SAVE_AND_UP, UP_SAVED, ButtonType.CANCEL)) {
            SAVE_AND_UP -> UnsavedChoice.SAVE_AND_UP
            UP_SAVED -> UnsavedChoice.UP_SAVED
            else -> UnsavedChoice.CANCEL
        }
    }

    /** Asked before an Up with unresolved variables and before Down and Remove Volumes; tests replace it. */
    var confirm: (question: String) -> Boolean = { question ->
        alert(question, ButtonType.OK, ButtonType.CANCEL) == ButtonType.OK
    }

    /** The pane's state; `null` until the host gives the window's sessions. */
    var model: ComposePaneModel? = null
        private set

    /** Runs the commands in the pane's Output; `null` until the [model] is. */
    var runner: CommandRunner<ComposeJob>? = null
        private set

    /** The services of the source by their line (0-based), as last parsed. */
    private var serviceAt: Map<Int, String> = emptyMap()

    private val reparse =
        PauseTransition(javafx.util.Duration.millis(reparsePause.inWholeMilliseconds.toDouble())).apply {
            setOnFinished { parseServices() }
        }
    private val textSubscription = editor.onTextChanged { reparse.playFromStart() }
    private val savedListener = ChangeListener<String> { _, _, _ -> model?.fileSaved() }

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
        editor.addGutterColumn(RUN_ICON_COLUMN) { paragraph -> serviceAt[paragraph]?.let(::runIcon) }
        editor.savedTextProperty.addListener(savedListener)
        pane.environment.chooseEnvFile = { folder ->
            FileChooser()
                .apply {
                    title = "Add Env File"
                    initialDirectory = folder.toFile().takeIf { it.isDirectory }
                }.showOpenDialog(root.scene?.window)
                ?.toPath()
        }
        // Through the command's entry point again, with its questions (ComposePaneModel.rerun).
        pane.runPane.rerun.setOnAction { model?.rerun() }
        parseServices()
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
            CommandRunner<ComposeJob>(
                runs,
                pane.runPane,
                { checkNotNull(file.parent) },
                { running, next -> confirmStop(running, next) },
                grace = stopGrace,
            )
        this.runner = runner
        activityWrapper.bind(runner.activity)
        val model =
            ComposePaneModel(
                session = session,
                store = store,
                file = { file },
                projectFolder = { host?.projectFolder },
                executable = { session.docker?.executable?.toString() ?: executableSetting() },
                host = ViewHost(),
                scheduler = FxScheduler,
                debounce = reloadDebounce,
            )
        model.order.set(serviceAt.values.toList())
        this.model = model
        pane.bind(model) { checkNotNull(file.parent) }
    }

    /** Up (explorer Run): shows the pane beside the source first. Does nothing outside an IDE window. */
    override fun run() {
        val model = model ?: return
        if (disposed) return
        showPane()
        model.up()
    }

    /** `up -d <service>` (the gutter's ▶), showing the pane beside the source first. */
    fun upService(service: String) {
        val model = model ?: return
        if (disposed) return
        showPane()
        model.upService(service)
    }

    private fun start(
        job: ComposeJob,
        command: () -> String,
        environment: Map<String, String>,
    ): Boolean {
        val runner = runner ?: return false
        showPane()
        return runner.start(job, command, environment = environment) == StartResult.STARTED
    }

    /** Shows the pane beside the source if only the source shows. */
    private fun showPane() {
        if (split.mode.get() == SplitMode.SOURCE) split.mode.set(SplitMode.SPLIT)
    }

    private fun parseServices() {
        val lines = serviceLines(editor.text)
        val byLine = lines.entries.associate { (service, line) -> line to service }
        if (byLine == serviceAt) return
        serviceAt = byLine
        editor.refreshGutter()
        model?.order?.set(lines.keys.toList())
    }

    private fun runIcon(service: String): Node =
        StackPane(Polygon(0.0, 0.0, 7.0, 4.5, 0.0, 9.0).apply { styleClass += "compose-run-triangle" }).apply {
            styleClass += "compose-run-icon"
            userData = service
            cursor = Cursor.HAND
            Tooltip.install(this, Tooltip("Up $service"))
            // Not pressed into the text: the caret stays where it is.
            addEventHandler(MouseEvent.MOUSE_PRESSED) { it.consume() }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY) upService(service)
                event.consume()
            }
        }

    private fun alert(
        question: String,
        vararg buttons: ButtonType,
    ): ButtonType =
        Alert(Alert.AlertType.CONFIRMATION, question, *buttons)
            .apply {
                initOwner(root.scene?.window)
                title = "Compose"
                headerText = null
            }.showAndWait()
            .orElse(ButtonType.CANCEL)

    /** Focuses the source, or the pane's Up while the pane is shown alone; a failed run no longer marks the tab. */
    override fun focus() {
        runner?.acknowledge()
        if (split.mode.get() != SplitMode.OTHER) editor.focus() else pane.toolbar.up.requestFocus()
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
        if (split.mode.get() == SplitMode.OTHER) pane.toolbar.up.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to], keeping its run, options and unsaved changes; the options are stored for [to]. */
    override fun moved(to: Path): Boolean {
        if (!editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        model?.moved()
        return true
    }

    override fun dispose() {
        disposed = true
        model?.close()
        runner?.dispose()
        reparse.stop()
        textSubscription.unsubscribe()
        editor.savedTextProperty.removeListener(savedListener)
        activityWrapper.unbind()
        editor.dispose()
    }

    /** The model's way to the editor, the dialogs, the runner and the window's Docker tool. */
    private inner class ViewHost : ComposeHost {
        override val isDirty: Boolean get() = editor.dirty.get()

        override fun askUnsaved(question: String) = this@ComposeView.askUnsaved(question)

        override fun save() = host?.save(file) ?: false

        override fun confirm(question: String) = this@ComposeView.confirm(question)

        override fun start(
            job: ComposeJob,
            command: () -> String,
            environment: Map<String, String>,
        ) = this@ComposeView.start(job, command, environment)

        override fun showLogs(id: String) {
            host?.windowService(Docker::class.java)?.showContainer(id, logs = true)
        }

        override fun openShell(
            id: String,
            name: String,
        ) {
            host?.windowService(Docker::class.java)?.openShell(id, name)
        }
    }

    private companion object {
        const val CSS = "/dev/sluide/docker/compose/compose-view.css"

        /** The width of the gutter column of the ▶. */
        const val RUN_ICON_COLUMN = 16.0
        val SAVE_AND_UP = ButtonType("Save and Up", ButtonBar.ButtonData.OK_DONE)
        val UP_SAVED = ButtonType("Up Saved Version", ButtonBar.ButtonData.OTHER)
    }
}
