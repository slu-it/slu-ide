package dev.sluide.docker.cli

import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.PortMapping
import dev.sluide.docker.model.Volume
import dev.sluide.docker.model.epochSeconds
import dev.sluide.docker.model.parseSize
import dev.sluide.docker.model.parseTime
import tools.jackson.databind.JsonNode
import java.time.Instant

/** The most ports one published range is expanded to; the rest of a larger range is left out. */
private const val MAX_RANGE_PORTS = 1024

private const val NONE = "<none>"

private val anonymousVolumeName = Regex("[0-9a-f]{64}")

/** The containers in the output of `ps -a --format json`: Docker's lines or Podman's array. */
internal fun parseContainers(text: String): List<Container> = jsonObjects(text).mapNotNull(::container)

/** The images in the output of `images --format json`, one per tag. */
internal fun parseImages(text: String): List<Image> = jsonObjects(text).flatMap(::images)

/**
 * The images of a plain listing and of `images --filter dangling=true` together, each once: Docker 29 with the
 * containerd image store leaves untagged images out of the plain listing, and `--all` would add the intermediate
 * layers of the classic store.
 */
internal fun mergeImages(
    listed: List<Image>,
    dangling: List<Image>,
): List<Image> = (listed + dangling).distinctBy { Triple(it.id, it.repository, it.tag) }

/** The volumes in the output of `volume ls --format json`; [dangling] names those no container uses, if known. */
internal fun parseVolumes(
    text: String,
    dangling: Set<String>?,
): List<Volume> = jsonObjects(text).mapNotNull { volume(it, dangling) }

/** The networks in the output of `network ls --format json`. */
internal fun parseNetworks(text: String): List<Network> = jsonObjects(text).mapNotNull(::network)

private fun container(node: JsonNode): Container? {
    val id = node.text("ID", "Id") ?: return null
    return Container(
        id = id,
        // Docker: "web-1" or "web-1,other/alias" with links; Podman: ["web-1"]. Inspect's names start with a slash.
        name = node.texts("Names").firstOrNull()?.removePrefix("/") ?: id.take(12),
        image = node.text("Image") ?: "",
        imageId = node.text("ImageID"),
        state = ContainerState.of(node.text("State")),
        status = node.text("Status") ?: "",
        ports = ports(node.field("Ports")),
        labels = node.labels("Labels"),
        createdAt = created(node),
        // Docker lists volume names and bind mount sources; Podman only mount targets. Names have no slash.
        volumes = node.texts("Mounts").filter { '/' !in it },
        networks = node.texts("Networks"),
    )
}

/** Podman's `Created` is Unix seconds (its `CreatedAt` reads "2 minutes ago"); Docker's `CreatedAt` a timestamp. */
private fun created(node: JsonNode): Instant? {
    val created = node.field("Created")
    if (created != null && created.isNumber) return epochSeconds(created.asLong())
    return parseTime(node.text("CreatedAt")) ?: parseTime(node.text("Created"))
}

private fun ports(node: JsonNode?): List<PortMapping> =
    when {
        node == null -> emptyList()
        node.isArray -> node.values().flatMap(::podmanPort)
        node.isValueNode -> parsePorts(node.asString())
        else -> emptyList()
    }

private val portPattern = Regex("""^(?:(.*):(\d+)(?:-(\d+))?->)?(\d+)(?:-(\d+))?/(\w+)$""")

/**
 * The ports in Docker's text, e.g. `127.0.0.1:8080->80/tcp, [::]:8443->443/tcp, 9000/tcp,
 * 0.0.0.0:7000-7001->7000-7001/tcp`; ranges are expanded.
 */
internal fun parsePorts(text: String): List<PortMapping> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
        val match = portPattern.matchEntire(part) ?: return@flatMap emptyList()
        val (ip, hostFirst, hostLast, containerFirst, containerLast, protocol) = match.destructured
        val containerStart = containerFirst.toInt()
        val count = ((containerLast.toIntOrNull() ?: containerStart) - containerStart + 1).coerceIn(1, MAX_RANGE_PORTS)
        val hostStart = hostFirst.toIntOrNull()
        (0 until count).map { offset ->
            PortMapping(
                hostIp = ip.removePrefix("[").removeSuffix("]").ifEmpty { null },
                hostPort = hostStart?.plus(if (hostLast.isEmpty()) 0 else offset),
                containerPort = containerStart + offset,
                protocol = protocol,
            )
        }
    }

/** Podman's port object: `{"host_ip": "", "container_port": 80, "host_port": 8080, "range": 1, "protocol": "tcp"}`. */
private fun podmanPort(node: JsonNode): List<PortMapping> {
    val containerPort = node.long("container_port", "containerPort", "PrivatePort")?.toInt() ?: return emptyList()
    val hostPort = node.long("host_port", "hostPort", "PublicPort")?.toInt()?.takeIf { it > 0 }
    val range = (node.long("range")?.toInt() ?: 1).coerceIn(1, MAX_RANGE_PORTS)
    return (0 until range).map { offset ->
        PortMapping(
            hostIp = node.text("host_ip", "hostIP", "IP"),
            hostPort = hostPort?.plus(offset),
            containerPort = containerPort + offset,
            protocol = node.text("protocol", "Type") ?: "tcp",
        )
    }
}

private fun images(node: JsonNode): List<Image> {
    val id = node.text("ID", "Id") ?: return emptyList()
    val sizeNode = node.field("Size")
    val size = if (sizeNode != null && sizeNode.isNumber) sizeNode.asLong() else parseSize(sizeNode?.scalarText()) ?: 0
    val digest = node.text("Digest")?.takeIf { it != NONE }
    val created = created(node)
    val podmanDangling = node.bool("Dangling")
    val tags: List<Pair<String?, String?>> =
        if (node.field("RepoTags") != null || node.field("Repository") == null) {
            // Podman: "RepoTags": ["docker.io/library/bash:5.2", …], or none for an untagged image.
            node
                .texts("RepoTags")
                .filter { it != "$NONE:$NONE" }
                .map(::splitReference)
                .ifEmpty { listOf(null to null) }
        } else {
            listOf(node.text("Repository")?.takeIf { it != NONE } to node.text("Tag")?.takeIf { it != NONE })
        }
    return tags.map { (repository, tag) ->
        Image(
            id = id,
            repository = repository,
            tag = tag,
            digest = digest,
            size = size,
            createdAt = created,
            dangling = podmanDangling ?: (repository == null && tag == null),
        )
    }
}

/** `registry:5000/name:tag` → (`registry:5000/name`, `tag`); a reference without a tag has tag `null`. */
private fun splitReference(reference: String): Pair<String?, String?> {
    val colon = reference.lastIndexOf(':')
    return if (colon > reference.lastIndexOf('/')) {
        reference.substring(0, colon) to reference.substring(colon + 1)
    } else {
        reference to null
    }
}

private fun volume(
    node: JsonNode,
    dangling: Set<String>?,
): Volume? {
    val name = node.text("Name") ?: return null
    val labels = node.labels("Labels")
    return Volume(
        name = name,
        driver = node.text("Driver") ?: "local",
        mountpoint = node.text("Mountpoint"),
        labels = labels,
        anonymous =
            "com.docker.volume.anonymous" in labels ||
                node.bool("Anonymous") == true ||
                anonymousVolumeName.matches(name),
        inUse = dangling?.let { name !in it },
    )
}

private fun network(node: JsonNode): Network? {
    val name = node.text("Name") ?: return null
    return Network(
        id = node.text("ID", "Id") ?: name,
        name = name,
        driver = node.text("Driver") ?: "",
        scope = node.text("Scope") ?: "local",
        labels = node.labels("Labels"),
        createdAt = parseTime(node.text("CreatedAt", "Created")),
    )
}
