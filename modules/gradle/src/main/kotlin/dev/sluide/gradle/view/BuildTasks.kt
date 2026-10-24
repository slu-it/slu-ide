package dev.sluide.gradle.view

import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.LoadedBuild
import dev.sluide.gradle.detect.GradleCommand
import dev.sluide.gradle.detect.GradleInstallation
import dev.sluide.gradle.detect.GradleLocator
import dev.sluide.gradle.realPath
import dev.sluide.gradle.run.RunState
import dev.sluide.gradle.tasks.ProjectSubtree
import dev.sluide.gradle.tasks.TaskModel
import dev.sluide.gradle.trust.GradleTrustCheck
import dev.sluide.projecttrust.ProjectTrustState
import javafx.application.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val log = System.getLogger(BuildTasks::class.java.name)

/**
 * Finds the Gradle of a view's build file and keeps its task [pane] showing the build's tasks (ADR 0085): it follows
 * the folder's trust, loads the task list through the [runner] when the window's [session] has none for the build (one
 * view per build at a time; the others wait for it), and follows what other views load and what saves make stale.
 * File system work runs on the session's thread, the rest on the FX thread. [found] is told what was found (`null`:
 * nothing).
 */
internal class BuildTasks(
    private val session: GradleSession,
    private val runner: GradleRunner,
    private val pane: TaskTreePane,
    private val file: () -> Path,
    private val projectFolder: () -> Path?,
    private val found: (GradleInstallation?) -> Unit,
) : AutoCloseable {
    /** The Gradle found for the file; `null` while none is (yet). */
    var installation: GradleInstallation? = null
        private set

    /** Identifies the build before it is loaded: the wrapper's folder, or the project root for the global `gradle`. */
    private var loadKey: Path? = null

    /** The loaded build shown, and the view's projects in it. */
    private var build: LoadedBuild? = null
    private var projects: ProjectSubtree? = null

    /** Whether the tasks are to be loaded once the folder's trust allows it. */
    private var loadWanted = false

    /** This view's own load is being prepared or runs; its claim ([GradleSession.claimLoad]) is released once. */
    private var loading = false

    /** The task list file of this view's load, deleted when the load ends or the view closes. */
    private var output: Path? = null

    /** Another view loads the same build; this one shows its result. */
    private var waiting = false
    private var detections = 0
    private var closed = false

    private val subscriptions: List<AutoCloseable> =
        session.projectTrust.state
            .subscribe { _, _ -> onFx(::trustChanged) }
            .let { trust -> listOf(AutoCloseable { trust.unsubscribe() }, session.onChange { onFx(::buildsChanged) }) }

    private val allowed: Boolean get() = session.projectTrust.allows(GradleTrustCheck.ID)

    /**
     * Finds Gradle for the file off the FX thread (the global check may take a while), and the loaded build it belongs
     * to unless [reload]; then shows the tasks, loading them if needed (always for [reload]).
     */
    fun detect(reload: Boolean) {
        if (closed) return
        val detection = ++detections
        val file = file()
        val root = projectFolder()
        session.execute {
            val found = GradleLocator.locate(file, root) { session.globalAvailable() }
            val key =
                when (found) {
                    is GradleInstallation.Wrapper -> realPath(found.folder)
                    GradleInstallation.Global -> root?.let(::realPath)
                    GradleInstallation.None -> null
                }
            val build = if (key != null && !reload) session.modelFor(file) else null
            val projects = build?.model?.subtreeOf(file)
            Platform.runLater { if (!closed && detection == detections) detected(found, key, build, projects, reload) }
        }
    }

    private fun detected(
        found: GradleInstallation,
        key: Path?,
        build: LoadedBuild?,
        projects: ProjectSubtree?,
        reload: Boolean,
    ) {
        installation = found.takeIf { key != null && it != GradleInstallation.None }
        loadKey = key
        found(installation)
        if (installation == null) return
        if (build != null && projects != null) show(build, projects)
        loadWanted = reload || build == null
        trustChanged()
    }

    /** Shows why Gradle is off; once the folder is trusted, the tasks, loading them if needed. */
    private fun trustChanged() {
        if (installation == null || closed) return
        if (!allowed) return pane.showNote(trustNote(session.projectTrust.state.get()) ?: TaskNote.UNTRUSTED)
        when {
            loadWanted -> load()
            waiting || loading -> pane.showNote(TaskNote.LOADING)
            else -> projects?.let { pane.showTasks(it.projects, it.own) }
        }
    }

    /** A view loaded or refreshed a build, a load ended, or a build file was saved. */
    private fun buildsChanged() {
        val key = loadKey
        if (closed || installation == null || key == null || loading) return
        if (waiting) {
            if (session.isLoading(key)) return
            waiting = false
            if (session.loadFailed(key)) return pane.showNote(TaskNote.FAILED_ELSEWHERE)
            // The other view's build may not be this file's (e.g. a file of buildSrc): then it loads its own.
            return resolve(onMissing = { if (allowed) load() else loadWanted = true })
        }
        val current = build ?: return resolve()
        val now = session.build(current.key) ?: return
        if (now.model === current.model) {
            build = now
            pane.showStale(now.stale)
        } else {
            resolve()
        }
    }

    /** Looks up the file's loaded build off the FX thread and shows it; [onMissing] if there is none. */
    private fun resolve(onMissing: () -> Unit = {}) {
        val file = file()
        val detection = detections
        session.execute {
            val build = session.modelFor(file)
            val projects = build?.model?.subtreeOf(file)
            Platform.runLater {
                if (closed || detection != detections || loading) return@runLater
                if (build == null || projects == null) return@runLater onMissing()
                loadWanted = false
                show(build, projects)
            }
        }
    }

    private fun show(
        build: LoadedBuild,
        projects: ProjectSubtree,
    ) {
        this.build = build
        this.projects = projects
        pane.showStale(build.stale)
        if (allowed) pane.showTasks(projects.projects, projects.own)
    }

    /**
     * Loads the task list through the runner, visibly in its terminal, unless another view loads the same build: then
     * waits for it. The temporary files are made on the session's thread.
     */
    private fun load() {
        val key = loadKey ?: return
        val installation = installation ?: return
        loadWanted = false
        pane.showNote(TaskNote.LOADING)
        if (!session.claimLoad(key)) {
            waiting = true
            return
        }
        loading = true
        val detection = detections
        session.execute {
            val files =
                try {
                    Result.success(session.taskListFile() to session.initScript())
                } catch (e: IOException) {
                    Result.failure(e)
                }
            Platform.runLater {
                val (output, script) =
                    files.getOrElse { error ->
                        runner.showProblem("✖ The task list cannot be prepared: ${error.message}")
                        return@runLater loadEnded(key, null, TaskNote.FAILED, failed = true)
                    }
                if (closed || detection != detections) return@runLater loadEnded(key, output, null, failed = false)
                this.output = output
                start(installation, key, output, script)
            }
        }
    }

    private fun start(
        installation: GradleInstallation,
        key: Path,
        output: Path,
        script: Path,
    ) {
        val result =
            runner.start(
                GradleJob.LoadTasks,
                command = { GradleCommand.loadTasks(installation, script, output) },
                ended = { state -> loaded(state, key, output) },
            )
        when (result) {
            StartResult.STARTED -> {
                Unit
            }

            StartResult.FAILED -> {
                loadEnded(key, output, TaskNote.FAILED, failed = true)
            }

            StartResult.CANCELLED -> {
                // The user kept the running job; views waiting for this load must not start one unasked either.
                loadEnded(key, output, null, failed = true)
                projects?.let { pane.showTasks(it.projects, it.own) } ?: pane.showNote(TaskNote.NOT_LOADED)
            }
        }
    }

    private fun loaded(
        state: RunState,
        key: Path,
        output: Path,
    ) {
        if (state !is RunState.Finished) {
            // Views waiting for it say so rather than load again; Refresh does.
            val note = if (state is RunState.Stopped) TaskNote.STOPPED else TaskNote.FAILED
            return loadEnded(key, output, note, failed = true)
        }
        val file = file()
        session.execute {
            val (build, problem) =
                try {
                    session.keep(TaskModel.parse(Files.readString(output))) to null
                } catch (e: IOException) {
                    null to "The task list could not be read: ${e.message}"
                } catch (e: IllegalArgumentException) {
                    null to (e.message ?: "The task list is not valid")
                }
            val projects = build?.model?.subtreeOf(file)
            Platform.runLater {
                if (build == null || projects == null) {
                    log.log(System.Logger.Level.WARNING, "Gradle tasks of $file: $problem")
                    if (!closed) runner.showProblem("✖ $problem")
                    return@runLater loadEnded(key, output, TaskNote.FAILED, failed = true)
                }
                loadEnded(key, output, null, failed = false)
                if (!closed) show(build, projects)
            }
        }
    }

    /**
     * Ends this view's load: deletes its [output] (quietly, on the session's thread), releases its claim unless that
     * happened already (the view closed while it ran), shows [note].
     */
    private fun loadEnded(
        key: Path,
        output: Path?,
        note: TaskNote?,
        failed: Boolean,
    ) {
        val release = loading
        loading = false
        this.output = null
        session.execute {
            output?.let(::deleteQuietly)
            if (release) session.loadEnded(key, failed)
        }
        if (!closed) note?.let(pane::showNote)
    }

    /** The file moved: finds its Gradle and build again. */
    fun moved() {
        build = null
        projects = null
        detect(reload = false)
    }

    /** The view is gone; a load it ran (ended with its terminal) no longer keeps other views waiting. */
    override fun close() {
        closed = true
        subscriptions.forEach(AutoCloseable::close)
        val key = loadKey
        if (loading && key != null) loadEnded(key, output, null, failed = false)
    }

    private fun onFx(action: () -> Unit) {
        if (Platform.isFxApplicationThread()) action() else Platform.runLater(action)
    }
}

/** The note while the folder's [state] keeps Gradle off: being checked, or not trusted; `null` once trusted. */
internal fun trustNote(state: ProjectTrustState): TaskNote? =
    when (state) {
        ProjectTrustState.Checking -> TaskNote.CHECKING
        is ProjectTrustState.Trusted -> null
        ProjectTrustState.Closed, is ProjectTrustState.Ask, is ProjectTrustState.Untrusted -> TaskNote.UNTRUSTED
    }

private fun deleteQuietly(file: Path) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        log.log(System.Logger.Level.DEBUG, "Could not delete $file: ${e.message}")
    }
}
