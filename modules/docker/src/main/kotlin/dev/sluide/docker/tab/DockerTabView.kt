package dev.sluide.docker.tab

import dev.sluide.docker.EngineState
import dev.sluide.docker.tab.details.ContainerDetailsPane
import dev.sluide.docker.tab.details.DetailsTab
import dev.sluide.docker.tab.details.ResourceDetailsPane
import dev.sluide.docker.tab.details.StreamViews
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.ResourceKey
import dev.sluide.docker.tab.tree.ResourceTreePane
import dev.sluide.docker.tab.tree.TreeRow
import javafx.beans.InvalidationListener
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Subscription
import java.net.URI

/**
 * The Docker tab's content (ADR 0118): the error bar, a header with the engine and Refresh, and below it the resource
 * tree beside the details of the selected row, or a note while the engine is not available (with Retry where it
 * failed). F5 refreshes. The logic is in [model] and the actions; this only lays out and wires. FX thread only.
 */
internal class DockerTabView(
    private val model: DockerTabModel,
    actions: RowActions,
    streamViews: StreamViews,
    openUrl: (URI) -> Unit,
) {
    private val logs = streamViews.logs(model.session)
    private val stats = streamViews.stats(model.session)

    private val errorText = Label().apply { styleClass += "docker-error-text" }
    private val errorBar =
        HBox(
            errorText.apply {
                isWrapText = true
                HBox.setHgrow(this, Priority.ALWAYS)
                maxWidth = Double.MAX_VALUE
            },
            Button("✕").apply {
                styleClass += "docker-error-close"
                setOnAction { model.error.set(null) }
            },
        ).apply { styleClass += "docker-error-bar" }

    private val engine = Label().apply { styleClass += "docker-engine" }
    val refresh = Button("Refresh").apply { setOnAction { model.refresh() } }
    private val header =
        HBox(
            Label("Docker").apply { styleClass += "docker-title" },
            engine,
            StackPane().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            refresh,
        ).apply { styleClass += "docker-header" }

    val treePane = ResourceTreePane(model, actions)
    val containerPane = ContainerDetailsPane(model, actions, logs, stats, openUrl)
    private val resourcePane = ResourceDetailsPane(model, actions, openUrl)
    private val placeholder =
        Label("Select a container, image, volume or network.").apply { styleClass += "docker-placeholder" }
    private val details = StackPane(placeholder)
    private val split = SplitPane(treePane.node, details).apply { setDividerPositions(0.35) }

    private val noteText = Label().apply { isWrapText = true }
    val retry = Button("Retry").apply { setOnAction { model.refresh() } }
    private val note = VBox(noteText, retry).apply { styleClass += "docker-note" }
    private val body = StackPane(split).apply { VBox.setVgrow(this, Priority.ALWAYS) }

    val node =
        VBox(errorBar, header, body).apply {
            styleClass += "docker-tab"
            stylesheets += checkNotNull(DockerTabView::class.java.getResource("docker-tab.css")).toExternalForm()
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.F5 && !event.isShortcutDown && !event.isAltDown && !event.isShiftDown) {
                    model.refresh()
                    event.consume()
                }
            }
        }

    private val subscriptions = mutableListOf<Subscription>()
    private val containersListener =
        InvalidationListener {
            model.selectedRow
                .get()
                ?.takeIf { it !is ContainerRow }
                ?.let(::showDetails)
        }

    init {
        subscriptions += model.error.subscribe { message -> showErrorBar(message) }
        subscriptions += model.session.state.subscribe { state -> showState(state) }
        subscriptions += model.selectedRow.subscribe { row -> showDetails(row) }
        // The overview of an image, volume or network also lists the containers using it, which change on their own;
        // the model's listener, added before, has updated the selected row by then.
        model.session.containers.addListener(containersListener)
    }

    /** Selects the container [id] (once it is listed) and, if [logs], its Logs tab. */
    fun showContainer(
        id: String,
        logs: Boolean,
    ) {
        model.select(ResourceKey.ContainerKey(id))
        if (logs) containerPane.select(DetailsTab.LOGS)
    }

    /** Shows [message] in the error bar. */
    fun showError(message: String) = model.error.set(message)

    fun dispose() {
        subscriptions.forEach(Subscription::unsubscribe)
        model.session.containers.removeListener(containersListener)
        model.close()
        logs.dispose()
        stats.dispose()
    }

    private fun showErrorBar(message: String?) {
        errorText.text = message.orEmpty()
        errorBar.isVisible = message != null
        errorBar.isManaged = message != null
    }

    private fun showState(state: EngineState) {
        engine.text = (state as? EngineState.Available)?.engine?.let(::engineText).orEmpty()
        val shown = engineNote(state)
        if (shown == null) {
            body.children.setAll(split)
        } else {
            noteText.text = shown.message
            retry.isVisible = shown.retry
            retry.isManaged = shown.retry
            body.children.setAll(note)
        }
    }

    private fun showDetails(row: TreeRow?) {
        containerPane.show(row as? ContainerRow)
        val shown =
            when (row) {
                null -> placeholder
                is ContainerRow -> containerPane.node
                else -> resourcePane.node.also { resourcePane.show(row) }
            }
        if (details.children.singleOrNull() !== shown) details.children.setAll(shown)
    }
}
