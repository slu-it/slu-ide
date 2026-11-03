package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.connect.SavedPasswords
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.SessionEvent
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.dialog.ConnectionEdit
import dev.sluide.databases.ui.dialog.ConnectionTester
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.secrets.Decrypted
import dev.sluide.secrets.Encrypted
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.CompletableFuture

/** A session whose state the test sets and whose calls it records; commit and rollback complete when the test says. */
internal open class FakeSession(
    override val config: ConnectionConfig,
) : DbSession {
    override var commitMode: CommitMode = config.commitMode
    override var pendingTransaction = false
    override var statementTimeoutSeconds = 0
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

    /** The Test Connection and the Save password reason of the last edit dialog. */
    var tester: ConnectionTester? = null
    var editSaveReason: String? = null
    var password: CharArray? = null

    /** Whether the prompt's Save password box is checked in the answer. */
    var save = false

    /** What each prompt was shown with: the Save password reason and whether the box started checked. */
    val prompts = mutableListOf<Pair<String?, Boolean>>()
    var delete = true
    var transactionEnd: TransactionEnd? = null
    var disconnect = true
    val errors = mutableListOf<Pair<String, String>>()

    override fun editConnection(
        initial: ConnectionConfig,
        isNew: Boolean,
        existingNames: Set<String>,
        tester: ConnectionTester,
        saveReason: String?,
    ): ConnectionEdit? {
        asked += "edit ${initial.name} new=$isNew names=${existingNames.sorted()}"
        this.tester = tester
        editSaveReason = saveReason
        return edit(initial)
    }

    override fun askPassword(
        config: ConnectionConfig,
        saveReason: String?,
        saveChecked: Boolean,
    ): PasswordAnswer? {
        asked += "password ${config.name}"
        prompts += saveReason to saveChecked
        return password?.copyOf()?.let { PasswordAnswer(it, save) }
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
 * Saved passwords without a key store: encrypt gives `v1:<id>:<password>`, which decrypt maps back for the same id
 * (another id or any other text is unreadable). Records the calls and the plain arrays it handed out.
 */
internal class FakeSavedPasswords : SavedPasswords {
    var reason: String? = null

    /** Makes encrypt fail with this reason. */
    var encryptFails: String? = null

    /** Makes decrypt answer NoKey with this reason. */
    var noKey: String? = null

    /** Runs inside decrypt, before it returns, e.g. to cancel the connect meanwhile. */
    var duringDecrypt: () -> Unit = {}

    /** The calls, in order: "encrypt <id> <password>", "decrypt <id> <value>". */
    val calls = mutableListOf<String>()

    /** The arrays decrypt returned; the caller must have zeroed them. */
    val handedOut = mutableListOf<CharArray>()

    override fun unavailableReason(): String? = reason

    override fun encrypt(
        id: String,
        password: CharArray,
    ): Encrypted {
        calls += "encrypt $id ${String(password)}"
        return encryptFails?.let(Encrypted::Failed) ?: Encrypted.Value(value(id, String(password)))
    }

    override fun decrypt(
        id: String,
        value: String,
    ): Decrypted {
        calls += "decrypt $id $value"
        duringDecrypt()
        noKey?.let { return Decrypted.NoKey(it) }
        val prefix = "v1:$id:"
        if (!value.startsWith(prefix)) return Decrypted.Unreadable
        return Decrypted.Plain(value.removePrefix(prefix).toCharArray().also(handedOut::add))
    }

    /** Whether every array decrypt handed out was zeroed. */
    fun allZeroed() = handedOut.all { array -> array.all { it == '\u0000' } }

    companion object {
        /** The value encrypt gives for [password] of connection [id]. */
        fun value(
            id: String,
            password: String,
        ) = "v1:$id:$password"
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
    val saved = FakeSavedPasswords()
    val sessions = mutableListOf<FakeSession>()

    /** The passwords Test Connection got (as strings, `null` for none), and the arrays themselves. */
    val testedWith = mutableListOf<String?>()
    val testArrays = mutableListOf<CharArray>()

    /** The passwords sessions were opened with (as strings, `null` for none). */
    val openedWith = mutableListOf<String?>()

    /** Makes the next connect fail. */
    var connectFailure: Throwable? = null

    /** Runs while a session opens, e.g. to cancel the connect meanwhile. */
    var duringOpen: () -> Unit = {}

    /** The password arrays the model zeroed. */
    val zeroed = mutableListOf<CharArray>()
    private val workers = mutableListOf<Runnable>()
    private var ids = 0

    val model =
        DatabasesTabModel(
            store,
            passwords,
            tasks,
            dialogs,
            { _, password ->
                testedWith += password?.let(::String)
                password?.let(testArrays::add)
                CompletableFuture.completedFuture("Test 1")
            },
            { config, password ->
                openedWith += password?.let(::String)
                duringOpen()
                connectFailure?.let { throw it }
                FakeSession(config).also(sessions::add)
            },
            { _, work -> workers += work },
            { it.run() },
            { "id${++ids}" },
            saved,
        ).also { model -> model.onZeroed = { zeroed += it } }

    /** Runs the queued workers, and those they queue in turn, until none is left. */
    fun runWorkers() {
        while (workers.isNotEmpty()) {
            val pending = workers.toList()
            workers.clear()
            pending.forEach(Runnable::run)
        }
    }

    /** Whether a worker waits to run. */
    fun workersPending() = workers.isNotEmpty()

    /** Connects [entry] with a cached password and returns its session. */
    fun connected(entry: ConnectionEntry): FakeSession {
        passwords.put(model.folder, entry.config.get().id, "secret".toCharArray())
        model.connect(entry)
        runWorkers()
        return entry.session.get() as FakeSession
    }
}
