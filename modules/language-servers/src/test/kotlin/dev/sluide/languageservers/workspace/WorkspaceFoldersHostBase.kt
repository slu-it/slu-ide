package dev.sluide.languageservers.workspace

import dev.sluide.languageservers.DocumentGate
import dev.sluide.languageservers.FakeProcess
import dev.sluide.languageservers.FakeScheduler
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.LogRecords
import dev.sluide.languageservers.RefreshKind
import dev.sluide.languageservers.ServerFeatures
import dev.sluide.languageservers.TestSpec
import dev.sluide.languageservers.WorkspaceFolderMode
import dev.sluide.languageservers.connection.ClientEvents
import dev.sluide.languageservers.connection.LspConnection
import dev.sluide.languageservers.connection.RunningServer
import dev.sluide.languageservers.connection.SessionClient
import dev.sluide.languageservers.files.FakeDirectoryWatcher
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.time.Duration.Companion.seconds

/**
 * [WorkspaceFolders] against a fake host, with the executors in the test's hands. Complements the session-level tests
 * of `WorkspaceFoldersTest`, which see only what a fake server received.
 */
internal abstract class WorkspaceFoldersHostBase {
    @TempDir
    lateinit var tmp: Path

    internal val root: Path get() = tmp.toAbsolutePath().normalize()

    internal class Doc(
        override val file: Path,
        var adoptResult: Boolean = false,
    ) : WorkspaceDocument {
        override var resolution: CompletableFuture<Resolution> = CompletableFuture()
        val reopened = CopyOnWriteArrayList<Unit>()

        override fun adopt(resolution: CompletableFuture<Resolution>): Boolean {
            this.resolution = resolution
            return adoptResult
        }

        override fun reopenOnServer() {
            reopened += Unit
        }

        fun resolvedTo(folder: Path?) =
            apply {
                resolution =
                    CompletableFuture.completedFuture(Resolution(folder, DocumentGate.SERVED))
            }
    }

    internal class Host : WorkspaceHost {
        val docs = mutableListOf<Doc>()
        var running: RunningServer? = null
        var defer = false
        val queued = ArrayDeque<() -> Unit>()
        var changed = 0
        val sent = CopyOnWriteArrayList<Pair<Path, FolderSent>>()
        val watchedFiles = CopyOnWriteArrayList<List<FileChange>>()
        val notifies = CopyOnWriteArrayList<Boolean>()

        override fun documents(): Collection<WorkspaceDocument> = docs.toList()

        override fun notify(
            start: Boolean,
            action: (RunningServer) -> Unit,
        ) {
            notifies += start
            run { running?.let(action) }
        }

        override fun onOutbound(action: (RunningServer?) -> Unit) = run { action(running) }

        private fun run(task: () -> Unit) = if (defer) queued.addLast(task) else task()

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst()()
        }

        override fun changed() {
            changed++
        }

        override fun folderSent(
            epoch: Int,
            folder: Path,
            sent: FolderSent,
        ) {
            this.sent += folder to sent
        }

        override fun watchedFilesChanged(changes: List<FileChange>) {
            watchedFiles.add(changes)
        }
    }

    internal class ManualWorker : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    internal val host = Host()
    internal val scheduler = FakeScheduler()
    internal val watcher = FakeDirectoryWatcher()
    internal val affectsAsked = CopyOnWriteArrayList<Path>()
    internal val watchedAsked = CopyOnWriteArrayList<Path>()
    internal val gateAsked = CopyOnWriteArrayList<Path>()
    internal val folderAsked = CopyOnWriteArrayList<Pair<Path, Path>>()
    internal val logs = LogRecords(Logger.getLogger(WorkspaceFolders::class.java.name))
    internal val processes = mutableListOf<FakeProcess>()
    internal val servers = mutableListOf<FakeServer>()

    /** What the spec answers per file name. */
    internal val answers = mutableMapOf<String, Path?>()

    /** The folders the spec names to watch per workspace folder. */
    internal val watchedBy = mutableMapOf<Path, List<Path>>()

    @Volatile
    internal var window: Path? = null

    internal fun spec(
        gating: Boolean = false,
        mode: WorkspaceFolderMode = WorkspaceFolderMode.WINDOW_FIRST,
        folders: ((Path, Path) -> Path?)? = null,
        watched: ((Path) -> List<Path>)? = null,
        affects: ((Path) -> Boolean)? = null,
    ) = TestSpec(
        gatesDocuments = gating,
        workspaceFolderMode = mode,
        folders = { file, project ->
            folderAsked.add(file to project)
            folders?.invoke(file, project) ?: answers[file.fileName.toString()]
        },
        watched = {
            watchedAsked.add(it)
            watched?.invoke(it) ?: watchedBy[it].orEmpty()
        },
        affects = {
            affectsAsked.add(it)
            affects?.invoke(it) ?: (it.fileName.toString() != "notes.txt")
        },
        gates = { file, _ ->
            gateAsked.add(file)
            DocumentGate(true, watched = watchedBy[file.parent].orEmpty())
        },
    )

    internal fun folders(
        spec: TestSpec = spec(),
        worker: Executor = Executor(Runnable::run),
        resolveWait: kotlin.time.Duration = 1.seconds,
        start: Boolean = true,
    ): WorkspaceFolders {
        window = root
        val created =
            WorkspaceFolders(
                spec,
                { window },
                worker,
                { it.run() },
                scheduler,
                1.seconds,
                resolveWait,
                watcher,
                host,
            )
        if (start) created.foldersFor(root)
        return created
    }

    internal fun running(): RunningServer {
        val server = FakeServer().also { servers += it }
        val process = FakeProcess("w#1", server).also { processes += it }
        val features = ServerFeatures()
        val events =
            object : ClientEvents {
                override fun configuration(item: ConfigurationItem): Any? = null

                override fun diagnostics(params: PublishDiagnosticsParams) = Unit

                override fun refresh(kind: RefreshKind) = Unit

                override fun featuresChanged() = Unit
            }
        val connection =
            LspConnection(process, SessionClient("w#1", { emptyList() }, features, events), features, "stopped")
                .also { it.listen() }
        return RunningServer(connection, 1).also { host.running = it }
    }

    @AfterEach
    fun cleanUp() {
        logs.close()
        processes.forEach { it.destroyTree() }
    }

    internal fun dir(relative: String): Path = root.resolve(relative).createDirectories()

    internal fun announce(
        folders: WorkspaceFolders,
        folder: Path,
    ) = folders.announce(CompletableFuture.completedFuture(Resolution(folder, DocumentGate.SERVED)), running())

    internal fun change(relative: String) = FileChange(root.resolve(relative), FileChangeKind.CHANGED)

    /** Runs what the scheduler holds due (the debounced work), once. */
    internal fun runDue() {
        val tasks = scheduler.due.toList()
        scheduler.due.clear()
        tasks.forEach { it.second.run() }
    }

    internal fun doc(relative: String) = Doc(root.resolve(relative)).also { host.docs += it }
}
