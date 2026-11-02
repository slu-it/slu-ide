package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.SessionEvent
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester
import dev.sluide.projectdata.ProjectDataStore
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture

/** A session whose state the test sets and whose calls it records; commit and rollback complete when the test says. */
internal class FakeSession(
    override val config: ConnectionConfig,
) : DbSession {
    override var commitMode: CommitMode = config.commitMode
    override var pendingTransaction = false
    override var running = false
    override var closed = false

    /** What was called, in order: "commit", "rollback", "cancel", "mode AUTO", "close". */
    val calls = mutableListOf<String>()
    val listeners = mutableListOf<(SessionEvent) -> Unit>()

    /** The futures of commit and rollback; completed by the test, or at once when [ends] is set. */
    val ending = mutableListOf<CompletableFuture<Unit>>()

    /** How commit and rollback end: `null` leaves them pending, else completed with (`null`) or failed with it. */
    var ends: Throwable? = null
    var endsAtOnce = true

    override fun setCommitMode(mode: CommitMode): CompletableFuture<Unit> {
        calls += "mode $mode"
        commitMode = mode
        return CompletableFuture.completedFuture(Unit)
    }

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> = error("not used")

    override fun cancel() {
        calls += "cancel"
    }

    override fun commit(): CompletableFuture<Unit> = end("commit")

    override fun rollback(): CompletableFuture<Unit> = end("rollback")

    private fun end(name: String): CompletableFuture<Unit> {
        calls += name
        val future = CompletableFuture<Unit>()
        ending += future
        if (endsAtOnce) {
            val failure = ends
            if (failure == null) {
                pendingTransaction = false
                future.complete(Unit)
            } else {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    override fun <T> withSession(
        changes: Boolean,
        work: (Connection) -> T,
    ): CompletableFuture<T> = error("not used")

    override fun <T> withMetadata(work: (Connection) -> T): CompletableFuture<T> = error("not used")

    override fun addListener(listener: (SessionEvent) -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: (SessionEvent) -> Unit) {
        listeners -= listener
    }

    override fun close() {
        calls += "close"
        closed = true
    }

    fun emit(event: SessionEvent) = listeners.toList().forEach { it(event) }
}

/** Dialogs with scripted answers that record what was asked. */
internal class FakeDialogs : DatabaseDialogs {
    val asked = mutableListOf<String>()
    var edit: (ConnectionConfig) -> ConnectionEdit? = { null }
    var password: CharArray? = null
    var delete = true
    var transactionEnd: TransactionEnd? = null
    var disconnect = true
    val errors = mutableListOf<Pair<String, String>>()

    override fun editConnection(
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
    ): ConnectionEdit? {
        asked += "edit ${initial.name} new=$isNew names=${existingNames.sorted()}"
        return edit(initial)
    }

    override fun askPassword(config: ConnectionConfig): CharArray? {
        asked += "password ${config.name}"
        return password?.copyOf()
    }

    override fun confirmDelete(name: String): Boolean {
        asked += "delete $name"
        return delete
    }

    override fun askTransactionEnd(name: String): TransactionEnd? {
        asked += "transaction $name"
        return transactionEnd
    }

    override fun confirmDisconnect(
        name: String,
        reasons: List<String>,
    ): Boolean {
        asked += "disconnect $name $reasons"
        return disconnect
    }

    override fun showError(
        title: String,
        message: String,
    ) {
        errors += title to message
    }
}

/**
 * A [DatabasesTabModel] over a real [ConnectionStore] in [dir], fake dialogs and sessions: connects run when the test
 * calls [runWorkers], FX work runs at once.
 */
internal class ModelFixture(
    dir: Path,
) {
    val data = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
    val store = ConnectionStore(data)
    val passwords = PasswordCache()
    val taskLines = mutableListOf<String>()
    val tasks = BackgroundTasks.forTests { taskLines += it }
    val dialogs = FakeDialogs()
    val sessions = mutableListOf<FakeSession>()

    /** The passwords sessions were opened with (as strings, `null` for none). */
    val openedWith = mutableListOf<String?>()

    /** Makes the next connect fail. */
    var connectFailure: Throwable? = null
    private val workers = mutableListOf<Runnable>()
    private var ids = 0

    val model =
        DatabasesTabModel(
            store,
            passwords,
            tasks,
            dialogs,
            { _, _ -> CompletableFuture.completedFuture("Test 1") },
            { config, password ->
                openedWith += password?.let(::String)
                connectFailure?.let { throw it }
                FakeSession(config).also(sessions::add)
            },
            { _, work -> workers += work },
            { it.run() },
            { "id${++ids}" },
        )

    fun runWorkers() {
        val pending = workers.toList()
        workers.clear()
        pending.forEach(Runnable::run)
    }

    /** Connects [entry] with a cached password and returns its session. */
    fun connected(entry: ConnectionEntry): FakeSession {
        passwords.put(model.folder, entry.config.get().id, "secret".toCharArray())
        model.connect(entry)
        runWorkers()
        return entry.session.get() as FakeSession
    }
}
