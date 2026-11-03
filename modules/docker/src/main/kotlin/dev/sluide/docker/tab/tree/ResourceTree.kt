package dev.sluide.docker.tab.tree

import dev.sluide.docker.model.ComposeProject
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.Volume
import dev.sluide.docker.model.containerTree
import dev.sluide.docker.tab.humanSize
import java.nio.file.Path

/** What a row of the Docker tab's tree stands for; selection and expansion follow it across reloads. */
internal sealed interface ResourceKey {
    data class Group(
        val kind: ResourceKind,
    ) : ResourceKey

    data class Project(
        val name: String,
    ) : ResourceKey

    data class ContainerKey(
        val id: String,
    ) : ResourceKey

    /** An image by its [reference]; an untagged one also by its [repository], as an id may be listed for several. */
    data class ImageKey(
        val reference: String,
        val repository: String? = null,
    ) : ResourceKey

    data class VolumeKey(
        val name: String,
    ) : ResourceKey

    data class NetworkKey(
        val id: String,
    ) : ResourceKey
}

/** A row of the tree: what it shows and the [key] it is kept by. */
internal sealed interface TreeRow {
    val key: ResourceKey

    /** The row's main text. */
    val label: String

    /** The dimmed text after the [label], empty if none. */
    val detail: String get() = ""

    /** The row's tooltip, `null` if none. */
    val tooltip: String? get() = null
}

/** A group: "Containers (3)". */
internal data class GroupRow(
    val kind: ResourceKind,
    val count: Int,
) : TreeRow {
    override val key = ResourceKey.Group(kind)
    override val label = "${groupTitle(kind)} ($count)"
}

/** A Compose project with its containers below it. */
internal data class ProjectRow(
    val project: ComposeProject,
) : TreeRow {
    override val key = ResourceKey.Project(project.name)
    override val label = project.name
    override val detail = if (project.local) "compose · this project" else "compose"
    override val tooltip = project.workingDir
}

internal data class ContainerRow(
    val container: Container,
) : TreeRow {
    override val key = ResourceKey.ContainerKey(container.id)
    override val label = container.name
    override val tooltip = "${container.image}\n${container.status}"
}

internal data class ImageRow(
    val image: Image,
) : TreeRow {
    override val key = ResourceKey.ImageKey(image.reference, image.repository.takeIf { image.tag == null })
    override val label = imageLabel(image)
    override val detail = humanSize(image.size)
    override val tooltip = image.id
}

internal data class VolumeRow(
    val volume: Volume,
) : TreeRow {
    override val key = ResourceKey.VolumeKey(volume.name)
    override val label = volumeLabel(volume)
    override val detail = volume.driver
    override val tooltip = volume.name
}

internal data class NetworkRow(
    val network: Network,
) : TreeRow {
    override val key = ResourceKey.NetworkKey(network.id)
    override val label = network.name
    override val detail = network.driver
}

/** A row with the rows below it. */
internal data class TreeNode(
    val row: TreeRow,
    val children: List<TreeNode> = emptyList(),
)

/** The four lists of the engine, as the tree is built from them. */
internal data class Resources(
    val containers: List<Container> = emptyList(),
    val images: List<Image> = emptyList(),
    val volumes: List<Volume> = emptyList(),
    val networks: List<Network> = emptyList(),
)

internal fun groupTitle(kind: ResourceKind): String =
    when (kind) {
        ResourceKind.CONTAINER -> "Containers"
        ResourceKind.IMAGE -> "Images"
        ResourceKind.VOLUME -> "Volumes"
        ResourceKind.NETWORK -> "Networks"
    }

/** `repository:tag`; an untagged image as `<none>` (`repository:<none>` if it has one) with the short id. */
internal fun imageLabel(image: Image): String =
    when {
        image.repository != null && image.tag != null -> image.reference
        image.repository != null -> "${image.repository}:<none> ${shortId(image.id)}"
        else -> "<none> ${shortId(image.id)}"
    }

/** The name; an anonymous volume's (a 64-character hash) shortened to 12 characters. */
internal fun volumeLabel(volume: Volume): String =
    if (volume.anonymous && volume.name.length > 12) volume.name.take(12) + "…" else volume.name

/** An id without `sha256:`, 12 characters long. */
internal fun shortId(id: String): String = id.removePrefix("sha256:").take(12)

/** The CSS class of a container's state dot. */
internal fun stateClass(state: ContainerState): String =
    when (state) {
        ContainerState.RUNNING -> "docker-state-running"
        ContainerState.PAUSED -> "docker-state-paused"
        ContainerState.RESTARTING -> "docker-state-restarting"
        else -> "docker-state-stopped"
    }

/**
 * The tree of the Docker tab: the groups Containers (Compose projects first, as [containerTree] sorts them, then
 * standalone containers), Images, Volumes and Networks, each with the number of rows it shows. A non-blank [filter]
 * keeps the rows whose name, image, tag or Compose project or service contains it, ignoring case; a project that
 * matches keeps all its containers.
 */
internal fun resourceTree(
    resources: Resources,
    projectFolder: Path?,
    filter: String,
): List<TreeNode> {
    val query = filter.trim().lowercase()

    fun matches(vararg texts: String?) = query.isEmpty() || texts.any { it?.lowercase()?.contains(query) == true }

    fun containerMatches(container: Container) =
        matches(container.name, container.image, container.composeProject, container.composeService)

    val tree = containerTree(resources.containers, projectFolder)
    val projects =
        tree.projects.mapNotNull { project ->
            val members =
                if (matches(
                        project.name,
                    )
                ) {
                    project.containers
                } else {
                    project.containers.filter(::containerMatches)
                }
            if (members.isEmpty()) {
                null
            } else {
                TreeNode(ProjectRow(project), members.map { TreeNode(ContainerRow(it)) })
            }
        }
    val standalone = tree.standalone.filter(::containerMatches).map { TreeNode(ContainerRow(it)) }
    val containerCount = projects.sumOf { it.children.size } + standalone.size
    val images =
        resources.images
            .filter { matches(it.repository, it.tag, it.reference, shortId(it.id)) }
            .sortedWith(compareBy<Image> { it.dangling }.thenBy(String.CASE_INSENSITIVE_ORDER) { imageLabel(it) })
            .map { TreeNode(ImageRow(it)) }
    val volumes =
        resources.volumes
            .filter { matches(it.name) }
            .sortedWith(compareBy<Volume> { it.anonymous }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { TreeNode(VolumeRow(it)) }
    val networks =
        resources.networks
            .filter { matches(it.name) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .map { TreeNode(NetworkRow(it)) }
    return listOf(
        TreeNode(GroupRow(ResourceKind.CONTAINER, containerCount), projects + standalone),
        TreeNode(GroupRow(ResourceKind.IMAGE, images.size), images),
        TreeNode(GroupRow(ResourceKind.VOLUME, volumes.size), volumes),
        TreeNode(GroupRow(ResourceKind.NETWORK, networks.size), networks),
    )
}

/** The row with [key] in [nodes] and below, `null` if none. */
internal fun findRow(
    nodes: List<TreeNode>,
    key: ResourceKey,
): TreeRow? {
    for (node in nodes) {
        if (node.row.key == key) return node.row
        findRow(node.children, key)?.let { return it }
    }
    return null
}

/** The node as [TreeSync] input: groups and Compose projects start expanded. */
internal fun TreeNode.toSyncNode(): SyncNode<ResourceKey, TreeRow> =
    SyncNode(row.key, row, children.map { it.toSyncNode() }, row is GroupRow || row is ProjectRow)
