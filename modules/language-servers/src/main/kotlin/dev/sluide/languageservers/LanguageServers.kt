package dev.sluide.languageservers

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.connection.SleepingScheduler
import dev.sluide.languageservers.files.DirectoryWatcher
import dev.sluide.languageservers.files.ProjectWatch
import dev.sluide.languageservers.files.WatcherFilter
import dev.sluide.languageservers.process.OsServerStarter
import dev.sluide.languageservers.process.ServerStarter
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * The language servers of one window (ADR 0142): one [LanguageServerSession] per spec, started when first used and
 * ended by [close] with the window. Servers serve files inside the project folder only; a window without a folder
 * has none. While a server starts or runs, the project folder is watched, and changes on disk reach the servers whose
 * file watchers want them ([filesChanged]).
 */
class LanguageServers internal constructor(
    /** The languages, in order of precedence for [specFor]. */
    val specs: List<LanguageServerSpec>,
    private val projectFolder: () -> Path?,
    trustGate: TrustGate,
    private val dispatch: (Runnable) -> Unit,
    starter: ServerStarter,
    scheduler: Scheduler,
    timing: ServerTiming,
    nanoTime: () -> Long,
    private val directoryWatcher: DirectoryWatcher = DirectoryWatcher { _, _ -> AutoCloseable {} },
    /** Runs the project watch's disk work; the default drops it, so that tests watch nothing unless they ask. */
    private val fileWorker: Executor = Executor {},
    private val watchOptions: ProjectWatchOptions = ProjectWatchOptions(),
    /**
     * The executor of each session that asks its spec for workspace folders (ADR 0151); a document's `didOpen` waits
     * for the answer. The default runs inline, so that tests get the answer at once.
     */
    folderWorker: (LanguageServerSpec) -> Executor = { Executor(Runnable::run) },
) : AutoCloseable {
    /**
     * @param projectFolder the window's folder, asked at each start (`null`: no servers).
     * @param trustGate the window's project trust (`ProjectTrust::awaitAllows`), for specs that run project code.
     * @param fileWatcher watches the project's folders while a server starts or runs.
     * @param dispatch hands results, listener calls and states to the FX thread.
     * @param projectWatch which folders of the project are watched.
     */
    constructor(
        specs: List<LanguageServerSpec>,
        projectFolder: () -> Path?,
        trustGate: TrustGate,
        fileWatcher: FileWatcher,
        dispatch: (Runnable) -> Unit = Platform::runLater,
        projectWatch: ProjectWatchOptions = ProjectWatchOptions(),
    ) : this(
        specs,
        projectFolder,
        trustGate,
        dispatch,
        OsServerStarter,
        SleepingScheduler,
        ServerTiming(),
        System::nanoTime,
        DirectoryWatcher(fileWatcher::watch),
        NamedThreads.single("sluide-lsp-files"),
        projectWatch,
        { spec -> NamedThreads.single("sluide-lsp-${spec.id}-folders") },
    )

    init {
        require(specs.map { it.id }.toSet().size == specs.size) { "Language server specs need distinct ids" }
    }

    private val sessions =
        specs.associateWith { spec ->
            LanguageServerSession(
                spec,
                projectFolder,
                trustGate,
                dispatch,
                starter,
                scheduler,
                timing,
                nanoTime,
                folderWorker(spec),
                directoryWatcher,
            )
        }

    private val watchLock = Any()
    private var projectWatch: ProjectWatch? = null
    private var closed = false

    init {
        sessions.values.forEach { session -> session.state.addListener { _, _, _ -> updateProjectWatch() } }
    }

    /** The spec that serves [file]: the first that matches, if the file is inside the project folder. */
    fun specFor(file: Path): LanguageServerSpec? {
        val folder = projectFolder()?.toAbsolutePath()?.normalize() ?: return null
        val absolute = file.toAbsolutePath().normalize()
        if (absolute == folder || !absolute.startsWith(folder)) return null
        return specs.firstOrNull { it.matches(absolute) }
    }

    /** The session of [spec], one per window; the server starts with its first use. */
    fun session(spec: LanguageServerSpec): LanguageServerSession =
        requireNotNull(sessions[spec]) { "${spec.id} is not a spec of these language servers" }

    /** The state of [spec]'s server; changes on the FX thread. */
    fun state(spec: LanguageServerSpec): ReadOnlyObjectProperty<ServerState> = session(spec).state

    /** Stops [spec]'s server and starts it again. */
    fun restart(spec: LanguageServerSpec) = session(spec).restart()

    /**
     * Tells every running server about the [events] its file watchers want (`workspace/didChangeWatchedFiles`): the
     * project watch's changes, and those a caller knows first (the explorer's moves and deletes; a save of an open
     * document counts as changed). Starts no server: one that does not run has no watchers. FX thread.
     *
     * Every change also reaches each session's workspace folders unfiltered: one its spec names
     * ([LanguageServerSpec.affectsWorkspaceFolders]) asks the workspace folders of the open documents below it again.
     */
    fun filesChanged(events: List<FileChange>) {
        if (events.isEmpty()) return
        sessions.values.forEach { it.workspaceFilesChanged(events) }
        for (session in sessions.values) {
            val filter = WatcherFilter(session.features.fileWatchers())
            if (filter.isEmpty) continue
            val wanted = events.filter(filter::matches)
            if (wanted.isEmpty()) continue
            val params = DidChangeWatchedFilesParams(wanted.map { FileEvent(LspUris.of(it.path), it.kind.type()) })
            session.notify { it.workspaceService.didChangeWatchedFiles(params) }
        }
    }

    /** Ends every server (`shutdown`, `exit`, kill after the timeouts) off the calling thread. */
    override fun close() {
        synchronized(watchLock) {
            closed = true
            projectWatch?.close()
            projectWatch = null
        }
        sessions.values.forEach { it.close() }
    }

    /** Watches the project while any server starts or runs. */
    private fun updateProjectWatch() {
        synchronized(watchLock) {
            val active = !closed && sessions.values.any { it.state.value.isActive() }
            if (active && projectWatch == null) {
                val root = projectFolder() ?: return
                projectWatch =
                    ProjectWatch(root, directoryWatcher, fileWorker, dispatch, watchOptions, ::filesChanged)
                        .also { it.start() }
            } else if (!active) {
                projectWatch?.close()
                projectWatch = null
            }
        }
    }

    /** The project watch while one runs; for tests. */
    internal val currentProjectWatch: ProjectWatch? get() = synchronized(watchLock) { projectWatch }
}

private fun ServerState.isActive() =
    this == ServerState.Starting || this == ServerState.Running || this is ServerState.Restarting

private fun FileChangeKind.type() =
    when (this) {
        FileChangeKind.CREATED -> FileChangeType.Created
        FileChangeKind.CHANGED -> FileChangeType.Changed
        FileChangeKind.DELETED -> FileChangeType.Deleted
    }
