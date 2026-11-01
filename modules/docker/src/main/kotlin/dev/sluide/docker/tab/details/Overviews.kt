package dev.sluide.docker.tab.details

import dev.sluide.docker.model.COMPOSE_PROJECT_LABEL
import dev.sluide.docker.model.COMPOSE_SERVICE_LABEL
import dev.sluide.docker.model.COMPOSE_WORKING_DIR_LABEL
import dev.sluide.docker.model.ComposeProject
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.PortMapping
import dev.sluide.docker.model.Volume
import dev.sluide.docker.tab.absoluteTime
import dev.sluide.docker.tab.humanSize
import dev.sluide.docker.tab.relativeTime
import dev.sluide.docker.tab.tree.shortId
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/** One value of an Overview row: text (with a tooltip) or a link that opens in the browser. */
internal sealed interface OverviewValue {
    data class Text(
        val text: String,
        val tooltip: String? = null,
    ) : OverviewValue

    data class Link(
        val text: String,
        val uri: URI,
    ) : OverviewValue
}

/** A row of an Overview: a label and its values, one per line; [collapsible] rows are shown folded (labels). */
internal data class OverviewRow(
    val label: String,
    val values: List<OverviewValue>,
    val collapsible: Boolean = false,
)

private fun row(
    label: String,
    vararg texts: String?,
): OverviewRow? =
    texts
        .filterNotNull()
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.let { OverviewRow(label, it.map { text -> OverviewValue.Text(text) }) }

private fun timeRow(
    label: String,
    time: Instant?,
    now: Instant,
): OverviewRow? = time?.let { OverviewRow(label, listOf(OverviewValue.Text(relativeTime(it, now), absoluteTime(it)))) }

/**
 * The address of a published TCP port in the browser: `http://localhost:<hostPort>` when it is published on all
 * addresses (`0.0.0.0`, `::` or none named), else on its host address; `null` for unpublished and non-TCP ports,
 * for scoped IPv6 addresses (`fe80::1%eth0`, browsers take no zone in a URL) and for addresses a URI cannot hold.
 */
internal fun portUri(port: PortMapping): URI? {
    val hostPort = port.hostPort ?: return null
    if (port.protocol != "tcp") return null
    val ip = port.hostIp?.trim().orEmpty()
    if ('%' in ip) return null
    val host =
        when (ip) {
            "", "0.0.0.0", "::" -> "localhost"
            else -> if (':' in ip) "[$ip]" else ip
        }
    return try {
        URI("http://$host:$hostPort")
    } catch (_: URISyntaxException) {
        null
    }
}

/** The Ports row's values: published TCP ports as links (each address once), the others as text. */
internal fun portValues(ports: List<PortMapping>): List<OverviewValue> =
    ports
        .map { port ->
            val target = "${port.containerPort}/${port.protocol}"
            val uri = portUri(port)
            when {
                uri != null -> OverviewValue.Link("${uri.host}:${uri.port} → $target", uri)
                port.hostPort != null -> OverviewValue.Text("${port.hostIp.orEmpty()}:${port.hostPort} → $target")
                else -> OverviewValue.Text("$target (not published)")
            }
        }.distinct()

/** The state with Docker's status text, e.g. "running · Up 3 minutes (healthy)". */
internal fun stateText(
    state: ContainerState,
    status: String?,
): String = listOfNotNull(state.name.lowercase(), status?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** The Overview of a container from its [details] and its list entry ([container], for the status text). */
internal fun containerOverview(
    details: ContainerDetails,
    container: Container?,
    now: Instant,
): List<OverviewRow> {
    val labels = details.labels
    return listOfNotNull(
        row("Image", details.image),
        row("State", stateText(details.state, container?.status)),
        row("Health", details.health),
        timeRow("Created", details.createdAt, now),
        timeRow("Started", details.startedAt, now),
        details.ports.takeIf { it.isNotEmpty() }?.let { OverviewRow("Ports", portValues(it)) },
        row(
            "Mounts",
            *details.mounts
                .map { mount ->
                    val source = mount.name ?: mount.source ?: mount.type
                    "$source → ${mount.destination}${if (mount.readWrite) "" else " (read-only)"}"
                }.toTypedArray(),
        ),
        row(
            "Networks",
            *details.networks
                .map { network -> listOfNotNull(network.name, network.ipAddress).joinToString(" ") }
                .toTypedArray(),
        ),
        row("Command", details.command.joinToString(" ")),
        row("Entrypoint", details.entrypoint.joinToString(" ")),
        row("Restarts", details.restartCount.takeIf { it > 0 }?.toString()),
        row("Exit code", details.exitCode?.takeIf { details.state != ContainerState.RUNNING }?.toString()),
        row("Compose project", labels[COMPOSE_PROJECT_LABEL]),
        row("Compose service", labels[COMPOSE_SERVICE_LABEL]),
        row("Working dir", labels[COMPOSE_WORKING_DIR_LABEL]),
        labelsRow(labels),
    )
}

private fun labelsRow(labels: Map<String, String>): OverviewRow? =
    labels.takeIf { it.isNotEmpty() }?.let { map ->
        OverviewRow(
            "Labels",
            map.toSortedMap().map { (key, value) -> OverviewValue.Text("$key=$value") },
            collapsible = true,
        )
    }

/** The containers created from [image]: by its reference, or by its id (a dangling image's containers name it so). */
internal fun imageUsers(
    image: Image,
    containers: List<Container>,
): List<Container> {
    val id = image.id.removePrefix("sha256:")
    return containers.filter { container ->
        container.image == image.reference ||
            (image.repository != null && image.tag == "latest" && container.image == image.repository) ||
            listOfNotNull(container.imageId, container.image)
                .map { it.removePrefix("sha256:") }
                .any { it.length >= 12 && id.startsWith(it) }
    }
}

private fun containerList(containers: List<Container>): List<OverviewValue> =
    containers.map { OverviewValue.Text("${it.name} (${it.state.name.lowercase()})", it.status) }

internal fun imageOverview(
    image: Image,
    containers: List<Container>,
    now: Instant,
): List<OverviewRow> =
    listOfNotNull(
        row("Reference", if (image.dangling) "<none>" else image.reference),
        row("Id", shortId(image.id)),
        row("Digest", image.digest),
        row("Size", humanSize(image.size)),
        timeRow("Created", image.createdAt, now),
        imageUsers(image, containers).takeIf { it.isNotEmpty() }?.let { OverviewRow("Containers", containerList(it)) },
    )

internal fun volumeOverview(
    volume: Volume,
    containers: List<Container>,
): List<OverviewRow> {
    val users = containers.filter { volume.name in it.volumes }
    val usedBy =
        when {
            users.isNotEmpty() -> OverviewRow("Used by", containerList(users))
            volume.inUse == true -> row("Used by", "a container")
            volume.inUse == false -> row("Used by", "no container")
            else -> null
        }
    return listOfNotNull(
        row("Name", volume.name),
        row("Driver", volume.driver),
        row("Mountpoint", volume.mountpoint),
        usedBy,
        labelsRow(volume.labels),
    )
}

internal fun networkOverview(
    network: Network,
    containers: List<Container>,
): List<OverviewRow> {
    val attached = containers.filter { network.name in it.networks || network.id in it.networks }
    return listOfNotNull(
        row("Name", network.name),
        row("Id", shortId(network.id)),
        row("Driver", network.driver),
        row("Scope", network.scope),
        attached.takeIf { it.isNotEmpty() }?.let { OverviewRow("Containers", containerList(it)) },
        labelsRow(network.labels),
    )
}

internal fun projectOverview(project: ComposeProject): List<OverviewRow> =
    listOfNotNull(
        row("Project", project.name),
        row("Working dir", project.workingDir),
        OverviewRow(
            "Containers",
            project.containers.map {
                val service = it.composeService?.let { s -> "$s: " } ?: ""
                OverviewValue.Text("$service${it.name} (${it.state.name.lowercase()})", it.status)
            },
        ),
    )

/** The value an Environment row shows: masked unless [shown]. */
internal fun envValueText(
    value: String,
    shown: Boolean,
): String = if (shown) value else "••••••"

/** What Copy on an Environment row puts on the clipboard. */
internal fun envCopyText(variable: EnvVariable): String = "${variable.name}=${variable.value}"
