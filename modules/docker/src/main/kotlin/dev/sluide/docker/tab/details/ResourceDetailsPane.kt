package dev.sluide.docker.tab.details

import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.tab.DockerTabModel
import dev.sluide.docker.tab.RowActions
import dev.sluide.docker.tab.tree.GroupRow
import dev.sluide.docker.tab.tree.ImageRow
import dev.sluide.docker.tab.tree.NetworkRow
import dev.sluide.docker.tab.tree.ProjectRow
import dev.sluide.docker.tab.tree.ResourceKey
import dev.sluide.docker.tab.tree.TreeRow
import dev.sluide.docker.tab.tree.VolumeRow
import dev.sluide.docker.tab.tree.groupTitle
import dev.sluide.docker.tab.tree.imageLabel
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.BorderPane
import java.net.URI
import java.time.Instant

/**
 * The details of a selected image, volume or network (Overview and Inspect), Compose project (an overview of its
 * containers) or group (its actions): a header with the row's actions above them. FX thread only.
 */
internal class ResourceDetailsPane(
    private val model: DockerTabModel,
    private val actions: RowActions,
    openUrl: (URI) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    private val header = DetailsHeader()
    private val overview = OverviewView(openUrl)
    private val inspect = InspectView()
    private val overviewTab = Tab(DetailsTab.OVERVIEW.title, overview.node).apply { userData = DetailsTab.OVERVIEW }
    private val inspectTab = Tab(DetailsTab.INSPECT.title, inspect.node).apply { userData = DetailsTab.INSPECT }
    private val tabs =
        TabPane(overviewTab, inspectTab).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }
    private val switch =
        DetailsTabSwitch(null, null, inspect::clear) { key ->
            val (kind, id) = inspected(key) ?: return@DetailsTabSwitch
            inspect.loading()
            model.load("inspect", key, onFailed = inspect::failed, onLoaded = inspect::show) { cli ->
                cli.inspect(kind, id)
            }
        }

    val node = BorderPane(tabs).apply { top = header.node }

    private var row: TreeRow? = null

    init {
        tabs.selectionModel.selectedItemProperty().addListener { _ -> update() }
    }

    /** Shows [row] (not a container); `null` shows nothing. */
    fun show(row: TreeRow?) {
        this.row = row
        val containers = model.session.containers.toList()
        header.title.text = row?.let(::titleOf).orEmpty()
        header.subtitle.text = row?.detail.orEmpty()
        header.actions.show(actions.of(row))
        val inspectable = row is ImageRow || row is VolumeRow || row is NetworkRow
        val wanted = if (inspectable) listOf(overviewTab, inspectTab) else listOf(overviewTab)
        if (tabs.tabs != wanted) tabs.tabs.setAll(wanted)
        when (row) {
            is ImageRow -> overview.show(imageOverview(row.image, containers, now()))
            is VolumeRow -> overview.show(volumeOverview(row.volume, containers))
            is NetworkRow -> overview.show(networkOverview(row.network, containers))
            is ProjectRow -> overview.show(projectOverview(row.project))
            is GroupRow -> overview.showNote(groupNote(row))
            else -> overview.showNote("")
        }
        update()
    }

    private fun update() {
        val tab = tabs.selectionModel.selectedItem?.userData as? DetailsTab ?: DetailsTab.OVERVIEW
        switch.update(tab, row?.key, null)
    }

    private fun titleOf(row: TreeRow): String =
        when (row) {
            is ImageRow -> imageLabel(row.image)
            is GroupRow -> groupTitle(row.kind)
            else -> row.label
        }

    private fun groupNote(row: GroupRow): String =
        when (row.kind) {
            ResourceKind.CONTAINER -> "${row.count} containers. Select one for its details and actions."
            ResourceKind.IMAGE -> "${row.count} images. Pull an image or remove the dangling ones."
            ResourceKind.VOLUME -> "${row.count} volumes. Create a volume or remove the unused ones."
            ResourceKind.NETWORK -> "${row.count} networks. Create a network or remove the unused ones."
        }

    private fun inspected(key: ResourceKey): Pair<ResourceKind, String>? =
        when (key) {
            is ResourceKey.ImageKey -> ResourceKind.IMAGE to key.reference
            is ResourceKey.VolumeKey -> ResourceKind.VOLUME to key.name
            is ResourceKey.NetworkKey -> ResourceKind.NETWORK to key.id
            else -> null
        }
}
