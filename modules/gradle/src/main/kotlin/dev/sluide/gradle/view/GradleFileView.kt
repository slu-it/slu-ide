package dev.sluide.gradle.view

import dev.sluide.fileviewers.CodeEditor
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SourceSplit
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.LoadedBuild
import dev.sluide.gradle.detect.GradleCommand
import dev.sluide.gradle.detect.GradleInstallation
import dev.sluide.gradle.detect.GradleLocator
import dev.sluide.gradle.run.RunState
import dev.sluide.gradle.tasks.GradleProject
import dev.sluide.gradle.tasks.TaskModel
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(GradleFileView::class.java.name)

/**
 * The view of `build.gradle` and `build.gradle.kts` (ADR 0085): the file's [editor] beside the build's tasks above the
 * terminal of their runs. Until Gradle is found (once the [host] gives the project folder and the window's
 * [GradleSession]) it looks like plain source; then the modes Source | Split | Gradle appear, in Split. The tasks load
 * once per build and window (shared through the session) when the folder's trust allows Gradle, and again on Refresh.
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
    val runPane = RunPane()

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
    var runner: GradleRunner? = null
        private set

    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)
    private var subscriptions: List<AutoCloseable> = emptyList()

    /** The Gradle found for the file; `null` while none is (yet). */
    var installation: GradleInstallation? = null
        private set

    /** The loaded build shown, and the view's projects in it. */
    private var build: LoadedBuild? = null
    private var projects: List<GradleProject>? = null

    /** Whether the tasks are to be loaded once the folder's trust allows it. */
    private var loadWanted = false
    private var loading = false
    private var detections = 0
    private var disposed = false

    init {
        (editor.node as? Region)?.minWidth = 0.0
        tasks.onRun = { runTasks(it, fromTree = true) }
        tasks.onRefresh = { detect(reload = true) }
        tasks.onReview = { session?.reviewTrust?.invoke() }
        runPane.rerun.setOnAction { runner?.lastTasks?.let { runTasks(it.paths, fromTree = false) } }
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

    private val allowed: Boolean get() = session?.projectTrust?.allows(GradleTrustCheck.ID) == true

    private fun attach(session: GradleSession) {
        this.session = session
        val runner = GradleRunner(session, runPane, { file.parent }, { r, n -> confirmStop(r, n) }, stopGrace)
        this.runner = runner
        activityWrapper.bind(runner.activity)
        val trust = session.projectTrust.state.subscribe { _, _ -> onFx(::trustChanged) }
        subscriptions =
            listOf(
                AutoCloseable { trust.unsubscribe() },
                session.onChange { onFx(::buildsChanged) },
            )
        // Not while the host is being set: the project folder may follow.
        Platform.runLater { detect(reload = false) }
    }

    /**
     * Finds Gradle for the file off the FX thread (the global check may take a while), and the loaded build it belongs
     * to unless [reload]; then shows the result.
     */
    private fun detect(reload: Boolean) {
        val session = session ?: return
        if (disposed) return
        val detection = ++detections
        val file = file
        val projectFolder = host?.projectFolder
        session.execute {
            val found = GradleLocator.locate(file, projectFolder) { session.globalAvailable() }
            val build = if (found != GradleInstallation.None && !reload) session.modelFor(file) else null
            val projects = build?.model?.subtreeOf(file)
            Platform.runLater { if (!disposed && detection == detections) detected(found, build, projects, reload) }
        }
    }

    private fun detected(
        found: GradleInstallation,
        build: LoadedBuild?,
        projects: List<GradleProject>?,
        reload: Boolean,
    ) {
        val before = installation
        installation = found.takeIf { it != GradleInstallation.None }
        toolbar.isVisible = installation != null
        toolbar.isManaged = installation != null
        if (installation == null) {
            split.mode.set(SplitMode.SOURCE)
            return
        }
        if (before == null) split.mode.set(SplitMode.SPLIT)
        if (build != null && projects != null) show(build, projects)
        loadWanted = reload || build == null
        trustChanged()
    }

    /** Shows the note while Gradle is off; once the folder is trusted, the tasks, loading them if needed. */
    private fun trustChanged() {
        if (installation == null || disposed) return
        if (!allowed) return tasks.showNote(TaskNote.UNTRUSTED)
        if (loadWanted) {
            load()
        } else {
            projects?.let(tasks::showTasks)
        }
    }

    /** Another view loaded or refreshed a build, or a build file was saved. */
    private fun buildsChanged() {
        val session = session ?: return
        if (disposed || installation == null || loading) return
        val current = build
        if (current == null) {
            // Another view of the same build may have loaded it.
            if (loadWanted && !allowed) return
            resolve(session)
            return
        }
        val now = session.build(current.key) ?: return
        if (now.model === current.model) {
            build = now
            tasks.showStale(now.stale)
        } else {
            resolve(session)
        }
    }

    /** Looks up the file's loaded build off the FX thread and shows it, if there is one. */
    private fun resolve(session: GradleSession) {
        val file = file
        val detection = detections
        session.execute {
            val build = session.modelFor(file) ?: return@execute
            val projects = build.model.subtreeOf(file)
            Platform.runLater {
                if (!disposed && detection == detections && !loading) {
                    loadWanted = false
                    show(build, projects)
                    if (allowed) tasks.showTasks(projects)
                }
            }
        }
    }

    private fun show(
        build: LoadedBuild,
        projects: List<GradleProject>,
    ) {
        this.build = build
        this.projects = projects
        tasks.showStale(build.stale)
        if (allowed) tasks.showTasks(projects)
    }

    /** Loads the task list through the runner, visibly in its terminal. */
    private fun load() {
        val session = session ?: return
        val runner = runner ?: return
        val installation = installation ?: return
        val output =
            try {
                session.taskListFile()
            } catch (e: IOException) {
                runner.showProblem("✖ No temporary file for the task list: ${e.message}")
                tasks.showNote(TaskNote.FAILED)
                return
            }
        val result =
            runner.start(
                GradleJob.LoadTasks,
                command = { GradleCommand.loadTasks(installation, session.initScript(), output) },
                ended = { state -> loaded(state, output) },
            )
        when (result) {
            StartResult.STARTED -> {
                loadWanted = false
                loading = true
                tasks.showNote(TaskNote.LOADING)
            }

            StartResult.FAILED -> {
                loadWanted = false
                tasks.showNote(TaskNote.FAILED)
                Files.deleteIfExists(output)
            }

            StartResult.CANCELLED -> {
                Files.deleteIfExists(output)
            }
        }
    }

    private fun loaded(
        state: RunState,
        output: Path,
    ) {
        val session = session ?: return
        if (state !is RunState.Finished) {
            loading = false
            Files.deleteIfExists(output)
            tasks.showNote(TaskNote.FAILED)
            return
        }
        val file = file
        session.execute {
            val (build, problem) =
                try {
                    session.keep(TaskModel.parse(Files.readString(output))) to null
                } catch (e: IOException) {
                    null to "The task list could not be read: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    null to (e.message ?: "The task list is not valid")
                } finally {
                    Files.deleteIfExists(output)
                }
            val projects = build?.model?.subtreeOf(file)
            Platform.runLater {
                loading = false
                if (disposed) return@runLater
                if (build == null || projects == null) {
                    log.log(System.Logger.Level.WARNING, "Gradle tasks of $file: $problem")
                    runner?.showProblem("✖ $problem")
                    tasks.showNote(TaskNote.FAILED)
                } else {
                    show(build, projects)
                }
            }
        }
    }

    private fun runTasks(
        paths: List<String>,
        fromTree: Boolean,
    ) {
        val runner = runner ?: return
        val installation = installation ?: return
        if (paths.isEmpty() || !allowed) return
        val arguments = runPane.arguments.text
        runner.start(
            GradleJob.Tasks(paths),
            command = { GradleCommand.run(installation, arguments, paths) },
            // The terminal takes the focus once it is in the scene, later on the FX thread.
            started = { if (fromTree) Platform.runLater { if (!disposed) tasks.tree.requestFocus() } },
        )
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

    override fun focusText() {
        if (split.mode.get() == SplitMode.OTHER) tasks.tree.requestFocus() else editor.focusText()
    }

    /** Follows the file to [to] if it has the same name (the same viewer shows it), and finds its Gradle again. */
    override fun moved(to: Path): Boolean {
        val name = to.fileName?.toString() ?: return false
        if (!name.equals(file.fileName.toString(), ignoreCase = true) || !editor.moved(to)) return false
        file = to.toAbsolutePath().normalize()
        build = null
        projects = null
        detect(reload = false)
        return true
    }

    override fun dispose() {
        disposed = true
        runner?.dispose()
        activityWrapper.unbind()
        subscriptions.forEach(AutoCloseable::close)
        subscriptions = emptyList()
        editor.dispose()
    }

    private fun onFx(action: () -> Unit) {
        if (Platform.isFxApplicationThread()) action() else Platform.runLater(action)
    }

    private companion object {
        const val CSS = "/dev/sluide/gradle/view/gradle-view.css"
    }
}
