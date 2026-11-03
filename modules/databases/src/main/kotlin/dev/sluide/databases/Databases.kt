package dev.sluide.databases

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.ConnectionStore
import dev.sluide.databases.connect.JdbcUrls
import dev.sluide.databases.connect.PasswordCache
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.DbSessions
import dev.sluide.databases.settings.DatabasesSettings
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.SqlSplitter
import dev.sluide.databases.ui.ConnectFailures
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.DatabaseDialogs
import dev.sluide.databases.ui.DatabasesTab
import dev.sluide.databases.ui.DatabasesTabModel
import dev.sluide.databases.ui.DatabasesTabView
import dev.sluide.databases.ui.FxDatabaseDialogs
import dev.sluide.databases.ui.cards.ConnectionCardList
import dev.sluide.databases.ui.console.ConnectionWorkspace
import dev.sluide.databases.ui.dialog.ConnectionTester
import dev.sluide.databases.ui.tree.ObjectTreeView
import dev.sluide.editorarea.EditorTab
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.stage.Window
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CompletableFuture

/**
 * The panes of the Databases tab: the cards ([cards], once per tab), and per connection its [workspace] (consoles over
 * results) and [tree], each created once per entry, on its first connect, and kept for the window lifetime.
 */
internal class DatabasesPanes(
    val cards: (DatabasesTabModel) -> Node,
    val workspace: (ConnectionEntry) -> Node,
    val tree: (ConnectionEntry) -> Node,
)

/**
 * The Databases tool of one window (ADR 0121): creates the Databases tab of the Services menu and holds its state, so
 * closing the tab keeps the connections; closing the window asks through [closeWarnings] and then [disconnectAll].
 * The `ide` module sets [showTab], [openTab], [window] and [openScratch] and binds [projectFolder].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Databases internal constructor(
    private val store: ConnectionStore,
    private val passwords: PasswordCache,
    /** The executed statements per connection, for the consoles. */
    internal val history: QueryHistory,
    /** Page size, fetch-all cap and statement timeout. */
    internal val settings: DatabasesSettings,
    /** The window's background tasks; connecting is one. */
    internal val backgroundTasks: BackgroundTasks,
    /** The dialogs of the tab over the window it is in. */
    private val dialogs: (owner: () -> Window?) -> DatabaseDialogs = ::FxDatabaseDialogs,
    /** Opens one JDBC connection (blocking, never on the FX thread). */
    private val connect: (ConnectionConfig, CharArray?) -> Connection = ::driverManagerConnection,
    /** Hands results of background work to the FX thread. */
    private val fx: (Runnable) -> Unit = Platform::runLater,
    /** Opens a session ([DbSessions.open]). */
    private val sessions: SessionOpener = SessionOpener(DbSessions::open),
    /** The editor font's CSS, bound to the consoles' editors. */
    private val editorCss: ObservableValue<String> = SimpleStringProperty(""),
) {
    @Autowired
    constructor(
        store: ConnectionStore,
        passwords: PasswordCache,
        history: QueryHistory,
        settings: DatabasesSettings,
        backgroundTasks: BackgroundTasks,
        fontSettings: FontSettings,
    ) : this(
        store,
        passwords,
        history,
        settings,
        backgroundTasks,
        ::FxDatabaseDialogs,
        editorCss = fontSettings.font(FontArea.EDITOR).map(FontSpec::toCss),
    )

    /** Opens or selects the window's Databases tab; set by ide. */
    var showTab: () -> Unit = {}

    /** Shows a tab in the window's editor area; set by ide. */
    var openTab: (EditorTab) -> Unit = {}

    /** The window this tool belongs to, the owner of its dialogs; set by ide. */
    var window: () -> Window? = { null }

    /** Opens text as a new scratch file named baseName with the extension, e.g. `orders` and `sql`; set by ide. */
    var openScratch: (baseName: String, extension: String, text: String) -> Unit = { _, _, _ -> }

    /** The window's project folder, whose connections the tab shows (`null`: the shared ones). Bound by ide. */
    val projectFolder: ObjectProperty<Path?> = SimpleObjectProperty()

    /** The UI packages plug in here before [createTab] is called; defaults are placeholders. */
    internal var panes: DatabasesPanes =
        DatabasesPanes(
            // The connection cards (left).
            cards = { model -> ConnectionCardList(model).node },
            // The consoles over their results (middle).
            workspace = { entry -> connectionWorkspace(entry).node },
            // The object tree (right).
            tree = { entry -> objectTree(entry).node },
        )

    // Copies of the settings that worker threads read per statement; updated on the FX thread.
    @Volatile private var timeoutSeconds = settings.statementTimeoutSeconds.get()

    @Volatile private var fetchAllCap = settings.fetchAllCap.get()

    /** Held strongly here, added weakly to the app-wide settings, so a closed window's bean is not kept. */
    private val settingsListener =
        InvalidationListener {
            timeoutSeconds = settings.statementTimeoutSeconds.get()
            fetchAllCap = settings.fetchAllCap.get()
        }

    /** The open Databases tab, if any. */
    private var tab: DatabasesTab? = null

    /** The connections; created with the first tab and kept for the window's lifetime. */
    private var model: DatabasesTabModel? = null

    init {
        settings.statementTimeoutSeconds.addListener(WeakInvalidationListener(settingsListener))
        settings.fetchAllCap.addListener(WeakInvalidationListener(settingsListener))
        projectFolder.addListener { _, _, folder -> model?.load(folder) }
    }

    /** A Databases tab over the window's connections; there is one at a time (the Services menu selects it). */
    fun createTab(): EditorTab {
        val view = DatabasesTabView(model(), panes)
        lateinit var created: DatabasesTab
        created = DatabasesTab(view) { if (tab === created) tab = null }
        tab = created
        return created
    }

    /**
     * For the window-close question: one line per connection with a pending transaction, a running statement or
     * unsubmitted edits, and what closing loses ("orders-dev: uncommitted transaction (rolled back)").
     */
    fun closeWarnings(): List<String> = model?.closeWarnings() ?: emptyList()

    /** Window closing (after the question): closes every session (DbSession.close rolls back); no question. */
    fun disconnectAll() {
        model?.disconnectAll()
    }

    /** The window's connections, created (and loaded) on first use. */
    internal fun model(): DatabasesTabModel =
        model ?: DatabasesTabModel(
            store,
            passwords,
            backgroundTasks,
            dialogs(::owner),
            tester(),
            ::openSession,
            fx = fx,
        ).also {
            model = it
            it.load(projectFolder.get())
        }

    /** The object tree of [entry]; Show DDL opens its scratch through the current [openScratch], alerts sit on [window]. */
    internal fun objectTree(entry: ConnectionEntry): ObjectTreeView =
        ObjectTreeView(
            entry,
            model(),
            entry.metadata,
            { name, ext, text -> openScratch(name, ext, text) },
            { window() },
        )

    /** The consoles of [entry] over their results; the limits are read per statement. */
    private fun connectionWorkspace(entry: ConnectionEntry) =
        ConnectionWorkspace(
            entry,
            model(),
            history,
            { settings.pageSize.get() },
            { settings.fetchAllCap.get() },
            editorCss,
            { window() },
            openScratch = { name, extension, text -> openScratch(name, extension, text) },
        )

    private fun owner(): Window? =
        window() ?: tab
            ?.view
            ?.node
            ?.scene
            ?.window

    /** Opens a session of [config] with the current statement timeout and fetch-all cap, read per statement. */
    internal fun openSession(
        config: ConnectionConfig,
        password: CharArray?,
    ): DbSession =
        sessions.open(
            config,
            password,
            connect,
            { SqlSplitter.isQuery(it, config.kind) },
            { timeoutSeconds },
            { fetchAllCap },
        )

    /**
     * Test Connection: opens one connection on a virtual thread, reads the server's product name and version and closes
     * it. A failure carries the card's message (no URL credentials, no password).
     */
    internal fun tester() =
        ConnectionTester { config, password ->
            val result = CompletableFuture<String>()
            NamedThreads.virtual("sluide-db-test") {
                try {
                    val version =
                        connect(config, password).use { connection ->
                            val meta = connection.metaData
                            "${meta.databaseProductName} ${meta.databaseProductVersion}"
                        }
                    result.complete(version)
                } catch (e: Throwable) {
                    result.completeExceptionally(SQLException(ConnectFailures.message(e, config, password)))
                }
            }
            result
        }
}

/** Opens a session: config, password, connect, isQuery, statement timeout, fetch-all cap ([DbSessions.open]). */
internal fun interface SessionOpener {
    fun open(
        config: ConnectionConfig,
        password: CharArray?,
        connect: (ConnectionConfig, CharArray?) -> Connection,
        isQuery: (String) -> Boolean,
        statementTimeoutSeconds: () -> Int,
        fetchAllCap: () -> Int,
    ): DbSession
}

private fun driverManagerConnection(
    config: ConnectionConfig,
    password: CharArray?,
): Connection = DriverManager.getConnection(JdbcUrls.url(config), JdbcUrls.properties(config, password))
