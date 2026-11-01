package dev.sluide.docker.dockerfile

import dev.sluide.docker.DockerSession
import dev.sluide.docker.EngineState
import dev.sluide.docker.events.Scheduler
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What [BuildPaneModel] needs of the Dockerfile view: its editor, dialogs, runner and the Docker tool. FX thread. */
internal interface BuildHost {
    /** Whether the editor has unsaved changes. */
    val isDirty: Boolean

    /** The editor's text, unsaved changes included. */
    val text: String

    /** Asks [question] about the unsaved changes. */
    fun askUnsaved(question: String): BuildUnsavedChoice

    /** Saves the editor's text as File → Save does (the host shows errors); `false` if it could not. */
    fun save(): Boolean

    /**
     * Starts [job] in the Output with the command line [command] (whose [IllegalArgumentException] the status line
     * shows), with [environment] laid over the terminal's; `true` if it started (not when the question about the
     * running one was answered no, or it failed to start). [ended] gets whether it ended with exit code 0, on the FX
     * thread.
     */
    fun start(
        job: BuildJob,
        command: () -> String,
        environment: Map<String, String>,
        ended: (succeeded: Boolean) -> Unit,
    ): Boolean

    /** Opens the Run dialog of the window's Docker tool for the image [reference]. */
    fun runImage(reference: String)

    /** Shows [message] as a failure in the Output's status line. */
    fun showProblem(message: String)
}

/**
 * The state behind one Build pane, without controls (ADR 0084): the [options] of the Dockerfile (loaded from the
 * [store], saved to it once [debounce] passed after a change, and on [close]), the file's stages and ARGs ([info], set
 * by the view from the editor's text), the [preview] of the command, whether [buildEnabled] and [runImageEnabled], and
 * the commands of the buttons. It subscribes to the [session] and follows the engine's state ([engine]) until
 * [close]d. FX thread; the debounce runs on the [scheduler] and comes back through [fx].
 */
internal class BuildPaneModel(
    private val session: DockerSession,
    private val store: DockerBuildStore,
    /** The Dockerfile (absolute); it changes when the file is moved. */
    private val file: () -> Path,
    private val projectFolder: () -> Path?,
    /** The docker executable for the command line. */
    private val executable: () -> String,
    private val host: BuildHost,
    private val scheduler: Scheduler,
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val debounce: Duration = 300.milliseconds,
    /** Whether a context folder exists. */
    private val isDirectory: (Path) -> Boolean = Files::isDirectory,
    /** The text of the file on disk, for a build of the saved version; `null` if it cannot be read. */
    private val savedText: (Path) -> String? = { path ->
        try {
            Files.readString(path)
        } catch (_: IOException) {
            null
        }
    },
) : AutoCloseable {
    private val optionsWrapper = ReadOnlyObjectWrapper(store.load(file(), projectFolder()))
    private val previewWrapper = ReadOnlyObjectWrapper<BuildPreview>(BuildPreview.Problem(""))
    private val enabledWrapper = ReadOnlyBooleanWrapper(false)
    private val runningWrapper = ReadOnlyBooleanWrapper(false)
    private val builtTagWrapper = ReadOnlyObjectWrapper<String?>(null)
    private val runImageWrapper = ReadOnlyBooleanWrapper(false)
    private val engineWrapper = ReadOnlyObjectWrapper(session.state.get())
    private val waitingWrapper = ReadOnlyBooleanWrapper(false)

    /** What is stored for the file; changed with [update]. */
    val options: ReadOnlyObjectProperty<BuildOptions> = optionsWrapper.readOnlyProperty

    /** The stages and ARGs of the editor's text, set by the view after each pause in typing. */
    val info = SimpleObjectProperty(DockerfileInfo(emptyList(), emptyList()))

    /** The engine's state, as the session has it, until [close]; the window's session outlives the view. */
    val engine: ReadOnlyObjectProperty<EngineState> = engineWrapper.readOnlyProperty

    /** Whether a build waits for the engine (it starts once the engine answers, or is dropped if it does not). */
    val waitingBuild: ReadOnlyBooleanProperty = waitingWrapper.readOnlyProperty

    /** The command line Build runs now, or why it cannot. */
    val preview: ReadOnlyObjectProperty<BuildPreview> = previewWrapper.readOnlyProperty

    /** Whether Build can run ([buildEnabled]). */
    val enabled: ReadOnlyBooleanProperty = enabledWrapper.readOnlyProperty

    /** Whether a build started here runs. */
    val running: ReadOnlyBooleanProperty = runningWrapper.readOnlyProperty

    /** The first tag of the last build if it succeeded, as entered; `null` before, while one runs and after a failure. */
    val builtTag: ReadOnlyObjectProperty<String?> = builtTagWrapper.readOnlyProperty

    /** Whether Run… can run the image ([runImageEnabled]). */
    val runImageEnabled: ReadOnlyBooleanProperty = runImageWrapper.readOnlyProperty

    /** The options not stored yet, saved when the debounce passes or on [close]. */
    private var pendingStore: AutoCloseable? = null
    private var unstored = false

    /** The ended callback that still counts: that of the build started last. */
    private var currentBuild: Any? = null

    /** What Rerun does: a build again, through [build] with its questions. */
    private var lastRun: (() -> Unit)? = null
    private var closed = false

    private val stateListener = InvalidationListener { refresh() }
    private val engineListener = ChangeListener<EngineState> { _, _, state -> engineChanged(state) }
    private val subscription: AutoCloseable

    init {
        session.state.addListener(engineListener)
        info.addListener(stateListener)
        options.addListener(stateListener)
        subscription = session.subscribe()
        refresh()
    }

    private val fileName: String get() = file().fileName.toString()

    /** Changes the options with [transform]; they are stored once [debounce] passed (typing in a field writes once). */
    fun update(transform: (BuildOptions) -> BuildOptions) {
        if (closed) return
        val old = options.get()
        val new = transform(old)
        if (new == old) return
        optionsWrapper.set(new)
        storeLater()
    }

    /** The file was moved: its options are stored for the new path, and the command follows it. */
    fun moved() {
        if (closed) return
        unstored = true
        storeNow()
        refresh()
    }

    /**
     * Build: asks about unsaved changes (Save and Build saves first; Build Saved Version builds with the ARGs of the
     * file on disk), then starts the build with the current options and records the Tags text in the history if it
     * started. While the engine is being reached, the build waits for it ([waitingBuild]); otherwise it does nothing
     * while [enabled] is not.
     */
    fun build() {
        if (closed) return
        if (!enabled.get()) {
            val connecting = engine.get().let { it == EngineState.Idle || it == EngineState.Checking }
            if (connecting && preview.get() is BuildPreview.Command) waitingWrapper.set(true)
            return
        }
        // The text as it is now, not as of the last pause in typing.
        var info = parseDockerfile(host.text)
        if (host.isDirty) {
            when (host.askUnsaved(unsavedBuildQuestion(fileName))) {
                BuildUnsavedChoice.SAVE_AND_BUILD -> {
                    if (!host.save()) return
                }

                BuildUnsavedChoice.BUILD_SAVED -> {
                    val saved = savedText(file())
                    if (saved == null) {
                        host.showProblem(savedUnreadableProblem(fileName))
                        return
                    }
                    info = parseDockerfile(saved)
                }

                BuildUnsavedChoice.CANCEL -> {
                    return
                }
            }
        }
        val options = options.get()
        val tag = tagsOf(options.tags).firstOrNull()
        val token = Any()
        val started =
            host.start(
                BuildJob(buildLabel(options, fileName), fileName),
                {
                    val args = BuildCommands.buildArgs(options, file(), info, isDirectory = isDirectory)
                    BuildCommands.commandLine(executable(), args)
                },
                BuildCommands.environment(options, info),
            ) { succeeded -> if (currentBuild === token) ended(succeeded, tag) }
        if (!started) return
        currentBuild = token
        lastRun = { build() }
        runningWrapper.set(true)
        builtTagWrapper.set(null)
        update { it.copy(history = withTagHistory(it.history, it.tags)) }
        refresh()
    }

    /** Rerun: a build again through [build], so with the current options and the same questions. */
    fun rerun() {
        if (!closed) lastRun?.invoke()
    }

    /** Run…: the Run dialog for the first tag of the last build, as entered; only while [runImageEnabled]. */
    fun runImage() {
        if (closed || !runImageEnabled.get()) return
        builtTag.get()?.let(host::runImage)
    }

    override fun close() {
        if (closed) return
        storeNow()
        closed = true
        currentBuild = null
        waitingWrapper.set(false)
        session.state.removeListener(engineListener)
        info.removeListener(stateListener)
        options.removeListener(stateListener)
        subscription.close()
    }

    private fun engineChanged(state: EngineState) {
        if (closed) return
        engineWrapper.set(state)
        refresh()
        when (state) {
            is EngineState.Available -> {
                if (waitingBuild.get()) {
                    waitingWrapper.set(false)
                    build()
                }
            }

            is EngineState.Unavailable, is EngineState.NotFound -> {
                waitingWrapper.set(false)
            }

            EngineState.Idle, EngineState.Checking -> {}
        }
    }

    private fun ended(
        succeeded: Boolean,
        tag: String?,
    ) {
        if (closed) return
        runningWrapper.set(false)
        builtTagWrapper.set(if (succeeded) tag else null)
        refresh()
    }

    /** Computes the preview and what is enabled again. */
    private fun refresh() {
        if (closed) return
        val preview =
            try {
                val options = options.get()
                val context = BuildCommands.contextOf(options, file())
                val args = BuildCommands.buildArgs(options, file(), info.get(), context, isDirectory)
                BuildPreview.Command(BuildCommands.commandLine(executable(), args))
            } catch (e: IllegalArgumentException) {
                BuildPreview.Problem(e.message ?: "Invalid options")
            }
        previewWrapper.set(preview)
        enabledWrapper.set(buildEnabled(engine.get(), preview))
        runImageWrapper.set(runImageEnabled(engine.get(), builtTag.get(), running.get()))
    }

    private fun storeLater() {
        unstored = true
        pendingStore?.close()
        pendingStore = scheduler.schedule(debounce) { fx { if (!closed) storeNow() } }
    }

    /** Stores the options now if a change is not stored yet. */
    private fun storeNow() {
        pendingStore?.close()
        pendingStore = null
        if (!unstored || closed) return
        unstored = false
        store.save(file(), projectFolder(), options.get())
    }
}
