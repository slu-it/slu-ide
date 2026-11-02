package dev.sluide.databases.ui

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.SessionEvent
import dev.sluide.databases.ui.tree.MetadataCache
import dev.sluide.databases.ui.tree.WorkspaceActions
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.scene.Node

/** Where a card's connection stands. */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * One connection card of the Databases tab (ADR 0121): its stored [config] and, while connected, its [session] with the
 * state the UI shows. Changed by [DatabasesTabModel] only; everything on the FX thread.
 */
class ConnectionEntry internal constructor(
    config: ConnectionConfig,
    /** The key of the connection's query history: `"<project folder or empty>|<id>"`. */
    val historyKey: String,
) {
    private val configWrapper = ReadOnlyObjectWrapper(config)
    val config: ReadOnlyObjectProperty<ConnectionConfig> = configWrapper.readOnlyProperty

    private val statusWrapper = ReadOnlyObjectWrapper(ConnectionStatus.DISCONNECTED)
    val status: ReadOnlyObjectProperty<ConnectionStatus> = statusWrapper.readOnlyProperty

    private val errorWrapper = ReadOnlyStringWrapper("")

    /** Why the last connect failed or the session broke; "" when none. Never holds the password or a URL with it. */
    val error: ReadOnlyStringProperty = errorWrapper.readOnlyProperty

    private val sessionWrapper = ReadOnlyObjectWrapper<DbSession?>()

    /** The current session, a new one per connect; `null` while not connected. */
    val session: ReadOnlyObjectProperty<DbSession?> = sessionWrapper.readOnlyProperty

    private val runningWrapper = ReadOnlyBooleanWrapper()
    val running: ReadOnlyBooleanProperty = runningWrapper.readOnlyProperty

    private val pendingWrapper = ReadOnlyBooleanWrapper()
    val pendingTransaction: ReadOnlyBooleanProperty = pendingWrapper.readOnlyProperty

    private val commitModeWrapper = ReadOnlyObjectWrapper(config.commitMode)

    /** The session's commit mode; the config's while not connected. */
    val commitMode: ReadOnlyObjectProperty<CommitMode> = commitModeWrapper.readOnlyProperty

    /** Set by the results package: whether there are unsubmitted grid edits. */
    var unsubmittedEdits: () -> Boolean = { false }

    /** What the tree asks of the middle pane; set by the workspace when it is created. */
    var workspaceActions: WorkspaceActions? = null

    /** The catalog of this connection for the tree and the completion; created on first use, survives reconnects. */
    val metadata: MetadataCache by lazy { MetadataCache(this) }

    /** The middle pane while connected, created on the first connect it is shown for; kept for the window lifetime. */
    internal var workspaceNode: Node? = null

    /** The right pane while connected, like [workspaceNode]. */
    internal var treeNode: Node? = null

    /** The connect in progress; `null` when none or once it was cancelled. */
    internal var attempt: ConnectAttempt? = null

    /** Whether a disconnect waits for its Commit or Roll Back; the status stays CONNECTED meanwhile. */
    internal var ending = false

    /** The listener on [session], so that events of an older session are told apart. */
    internal var listener: ((SessionEvent) -> Unit)? = null

    internal fun setConfig(value: ConnectionConfig) {
        configWrapper.set(value)
        if (sessionWrapper.get() == null) commitModeWrapper.set(value.commitMode)
    }

    internal fun connecting() {
        errorWrapper.set("")
        statusWrapper.set(ConnectionStatus.CONNECTING)
    }

    internal fun connected(session: DbSession) {
        sessionWrapper.set(session)
        errorWrapper.set("")
        runningWrapper.set(session.running)
        pendingWrapper.set(session.pendingTransaction)
        commitModeWrapper.set(session.commitMode)
        statusWrapper.set(ConnectionStatus.CONNECTED)
    }

    /** Back to not connected: [message] is shown as the error (status ERROR), or none (DISCONNECTED). */
    internal fun ended(message: String?) {
        sessionWrapper.set(null)
        listener = null
        runningWrapper.set(false)
        pendingWrapper.set(false)
        commitModeWrapper.set(configWrapper.get().commitMode)
        errorWrapper.set(message ?: "")
        statusWrapper.set(if (message == null) ConnectionStatus.DISCONNECTED else ConnectionStatus.ERROR)
    }

    internal fun setRunning(value: Boolean) = runningWrapper.set(value)

    internal fun setPending(value: Boolean) = pendingWrapper.set(value)

    internal fun setCommitMode(value: CommitMode) = commitModeWrapper.set(value)
}

/** One connect of an entry: its background task, and whether it was cancelled (a late session is then closed). */
internal class ConnectAttempt(
    val task: BackgroundTask,
) {
    var cancelled = false
}
