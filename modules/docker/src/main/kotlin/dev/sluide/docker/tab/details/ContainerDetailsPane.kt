package dev.sluide.docker.tab.details

import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.tab.DetailsLoad
import dev.sluide.docker.tab.DockerTabModel
import dev.sluide.docker.tab.RowActions
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.ResourceKey
import javafx.collections.SetChangeListener
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.BorderPane
import java.net.URI
import java.time.Instant

/**
 * The details of the selected container: a header with its name, state and actions, and the tabs Overview, Logs,
 * Stats, Environment and Inspect. [logs] and [stats] stream only while their tab is shown ([DetailsTabSwitch]). FX
 * thread only.
 */
internal class ContainerDetailsPane(
    private val model: DockerTabModel,
    private val actions: RowActions,
    private val logs: StreamView,
    private val stats: StreamView,
    openUrl: (URI) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val header = DetailsHeader()
    private val overview = OverviewView(openUrl)
    private val environment = EnvironmentView()
    private val inspect = InspectView()
    private val tabs =
        TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            DetailsTab.entries.forEach { detailsTab ->
                tabs +=
                    Tab(detailsTab.title, contentOf(detailsTab)).apply {
                        userData = detailsTab
                        styleClass += "docker-details-${detailsTab.name.lowercase()}"
                    }
            }
        }
    private val switch =
        DetailsTabSwitch(logs, stats, inspect::clear) { key ->
            val id = (key as? ResourceKey.ContainerKey)?.id ?: return@DetailsTabSwitch
            inspect.loading()
            model.load("inspect", key, onFailed = inspect::failed, onLoaded = inspect::show) { cli ->
                cli.inspect(ResourceKind.CONTAINER, id)
            }
        }

    val node = BorderPane(tabs).apply { top = header.node }

    /** The container shown, `null` while another kind of row (or none) is selected. */
    private var row: ContainerRow? = null

    /** The details tab selected. */
    val selectedTab: DetailsTab get() = tabs.selectionModel.selectedItem?.userData as? DetailsTab ?: DetailsTab.OVERVIEW

    init {
        tabs.selectionModel.selectedItemProperty().addListener { _ -> update() }
        model.containerDetails.addListener { _ -> showDetails() }
        model.running.addListener(SetChangeListener { _ -> showActions() })
    }

    /** Shows [row], or stops the streams if `null`. */
    fun show(row: ContainerRow?) {
        this.row = row
        if (row != null) {
            header.title.text = row.container.name
            header.subtitle.text = row.container.status
            showActions()
            showDetails()
        }
        update()
    }

    /** Selects the details tab [tab]. */
    fun select(tab: DetailsTab) {
        tabs.selectionModel.select(tabs.tabs.first { it.userData == tab })
    }

    private fun showActions() = header.actions.show(actions.of(row))

    private fun showDetails() {
        val container = row?.container ?: return
        when (val load = model.containerDetails.get()) {
            null, DetailsLoad.Loading -> {
                overview.showNote("Loading…")
                environment.show(emptyList())
            }

            is DetailsLoad.Failed -> {
                overview.showNote(load.message)
                environment.show(emptyList())
            }

            is DetailsLoad.Loaded -> {
                overview.show(containerOverview(load.details, container, now()))
                environment.show(load.details.env)
            }
        }
    }

    private fun update() = switch.update(selectedTab, row?.key, row?.container?.id)

    private fun contentOf(tab: DetailsTab) =
        when (tab) {
            DetailsTab.OVERVIEW -> overview.node
            DetailsTab.LOGS -> logs.node
            DetailsTab.STATS -> stats.node
            DetailsTab.ENVIRONMENT -> environment.node
            DetailsTab.INSPECT -> inspect.node
        }
}
