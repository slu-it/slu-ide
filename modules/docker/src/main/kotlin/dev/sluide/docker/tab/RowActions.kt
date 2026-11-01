package dev.sluide.docker.tab

import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.GroupRow
import dev.sluide.docker.tab.tree.ImageRow
import dev.sluide.docker.tab.tree.NetworkRow
import dev.sluide.docker.tab.tree.ProjectRow
import dev.sluide.docker.tab.tree.TreeRow
import dev.sluide.docker.tab.tree.VolumeRow

/** An action offered for a tree row, as a header button and a context menu item. */
internal data class RowAction(
    val title: String,
    val enabled: Boolean,
    val run: () -> Unit,
)

/**
 * The actions of each kind of row: the container commands, Run… and Remove for images, Remove for volumes and
 * networks, and the group actions (Pull…, Create…, Prune…; a prune is disabled while it would remove nothing).
 * Compose projects have none in this round.
 */
internal class RowActions(
    private val containers: ContainerActions,
    private val resources: ResourceActions,
    /** Run… for an image reference: opens the Run Container dialog. */
    private val runImage: (String) -> Unit,
) {
    fun of(row: TreeRow?): List<RowAction> =
        when (row) {
            is ContainerRow -> {
                ContainerCommand.entries.map { command ->
                    RowAction(command.title, containers.enabled(command, row.container)) {
                        containers.perform(command, row.container)
                    }
                }
            }

            is ImageRow -> {
                listOf(
                    RowAction("Run…", true) { runImage(row.image.reference) },
                    RowAction("Remove", true) { resources.removeImage(row.image) },
                )
            }

            is VolumeRow -> {
                listOf(RowAction("Remove", true) { resources.removeVolume(row.volume) })
            }

            is NetworkRow -> {
                listOf(RowAction("Remove", resources.canRemove(row.network)) { resources.removeNetwork(row.network) })
            }

            is GroupRow -> {
                groupActions(row.kind)
            }

            is ProjectRow, null -> {
                emptyList()
            }
        }

    private fun groupActions(kind: ResourceKind): List<RowAction> {
        val candidates = resources.pruneCandidates()
        return when (kind) {
            ResourceKind.CONTAINER -> {
                emptyList()
            }

            ResourceKind.IMAGE -> {
                listOf(
                    RowAction("Pull…", true, resources::pull),
                    RowAction("Prune Dangling Images…", candidates.images.isNotEmpty(), resources::pruneImages),
                )
            }

            ResourceKind.VOLUME -> {
                listOf(
                    RowAction("Create Volume…", true, resources::createVolume),
                    RowAction("Prune Unused Volumes…", candidates.volumes.isNotEmpty(), resources::pruneVolumes),
                )
            }

            ResourceKind.NETWORK -> {
                listOf(
                    RowAction("Create Network…", true, resources::createNetwork),
                    RowAction("Prune Unused Networks…", candidates.networks.isNotEmpty(), resources::pruneNetworks),
                )
            }
        }
    }
}
