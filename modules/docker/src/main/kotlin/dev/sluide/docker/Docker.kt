package dev.sluide.docker

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.docker.tab.ContainerActions
import dev.sluide.docker.tab.DockerDialogs
import dev.sluide.docker.tab.DockerTab
import dev.sluide.docker.tab.DockerTabModel
import dev.sluide.docker.tab.DockerTabView
import dev.sluide.docker.tab.FxDockerDialogs
import dev.sluide.docker.tab.ResourceActions
import dev.sluide.docker.tab.RowActions
import dev.sluide.docker.tab.details.StreamViews
import dev.sluide.docker.tab.shellCommand
import dev.sluide.editorarea.EditorTab
import dev.sluide.terminal.TerminalStartException
import dev.sluide.terminal.Terminals
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.stage.Window
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.file.Path

/**
 * The Docker tool of one window (ADR 0118): creates the Docker tab of the Services menu, shows a container in it
 * ([showContainer]) and opens shells in containers ([openShell]), also for other views. The `ide` module sets
 * [showTab], [openTab] and [openUrl] and binds [projectFolder].
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Docker internal constructor(
    private val session: DockerSession,
    private val backgroundTasks: BackgroundTasks,
    /** Starts a command in a terminal tab: folder, name, command, environment ([Terminals.open]). */
    private val openTerminal: (Path, String, String, Map<String, String>) -> EditorTab,
    /** The folder of shells while no project is open. */
    private val home: Path,
    /** The dialogs of the tab's actions over the window it is in. */
    private val dialogs: (owner: () -> Window?) -> DockerDialogs = ::FxDockerDialogs,
    /** The Logs and Stats views of a tab. */
    private val streamViews: StreamViews = StreamViews(),
    /** Hands results of background work to the FX thread. */
    private val fx: (Runnable) -> Unit = Platform::runLater,
) {
    @Autowired
    constructor(session: DockerSession, terminals: Terminals, backgroundTasks: BackgroundTasks) : this(
        session,
        backgroundTasks,
        { folder, name, command, environment -> terminals.open(folder, name, command, environment) },
        Path.of(System.getProperty("user.home")),
    )

    /** Opens or selects the window's Docker tab; set by ide. */
    var showTab: () -> Unit = {}

    /** Shows a tab in the window's editor area, e.g. a container's shell; set by ide. */
    var openTab: (EditorTab) -> Unit = {}

    /** Opens a web page (a container's published port) in the system browser; set by ide. */
    var openUrl: (URI) -> Unit = {}

    /** The window's project folder; its Compose projects come first, and shells start in it. Bound by ide. */
    val projectFolder: ObjectProperty<Path?> = SimpleObjectProperty()

    /** The open Docker tab, if any. */
    private var tab: DockerTab? = null

    /** A new Docker tab; it watches the engine through the window's [DockerSession] until it is closed. */
    fun createTab(): EditorTab {
        val model = DockerTabModel(session, fx).apply { projectFolder.bind(this@Docker.projectFolder) }
        lateinit var view: DockerTabView
        val askers = dialogs { view.node.scene?.window }
        val actions =
            RowActions(
                ContainerActions(model, askers, ::openShell),
                ResourceActions(model, askers, backgroundTasks),
            )
        view = DockerTabView(model, actions, streamViews) { openUrl(it) }
        lateinit var created: DockerTab
        created =
            DockerTab(view) {
                model.projectFolder.unbind()
                if (tab === created) tab = null
            }
        tab = created
        return created
    }

    /** Shows the Docker tab with the container [id] selected, and its Logs tab if [logs]. */
    fun showContainer(
        id: String,
        logs: Boolean,
    ) {
        showTab()
        tab?.view?.showContainer(id, logs)
    }

    /**
     * Opens a terminal tab named "<[name]> (shell)" with a shell in the container [containerId]: bash if it has one,
     * else sh. A failure shows in the Docker tab's error bar.
     */
    fun openShell(
        containerId: String,
        name: String,
    ) {
        val docker = session.docker
        if (docker == null) {
            showError("Docker is not available")
            return
        }
        val folder = projectFolder.get() ?: home
        try {
            openTab(
                openTerminal(
                    folder,
                    "$name (shell)",
                    shellCommand(docker.executable.toString(), containerId),
                    docker.environment,
                ),
            )
        } catch (e: TerminalStartException) {
            showError("The shell cannot be started. ${e.message}")
        }
    }

    private fun showError(message: String) {
        if (tab == null) showTab()
        tab?.view?.showError(message)
    }
}
