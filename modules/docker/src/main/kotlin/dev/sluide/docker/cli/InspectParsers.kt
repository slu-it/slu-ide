package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EngineInfo
import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.model.Mount
import dev.sluide.docker.model.NetworkAttachment
import dev.sluide.docker.model.PortMapping
import dev.sluide.docker.model.parseTime
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper

private val printer =
    DefaultPrettyPrinter(
        Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER),
    ).withObjectIndenter(DefaultIndenter("  ", "\n"))
        .withArrayIndenter(DefaultIndenter("  ", "\n"))

private val prettyJson: JsonMapper =
    JsonMapper
        .builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .defaultPrettyPrinter(printer)
        .build()

/**
 * The engine in the output of `version --format json`. Podman's CLI writes `APIVersion` and `OsArch` in its client
 * part (also when a `docker` script runs it), Docker's CLI `ApiVersion`; a server part is missing for Podman without
 * a remote machine, then the client's version is the engine's. Docker's client part names the current context.
 *
 * @throws DockerException if the output has no version.
 */
internal fun parseEngine(text: String): EngineInfo {
    val root = jsonObjects(text).firstOrNull() ?: throw DockerException("The CLI printed no version")
    val client = root.field("Client")
    val server = root.field("Server")
    val podman = client != null && (client.has("OsArch") || client.has("APIVersion"))
    val serverVersion =
        server?.text("Version") ?: client?.text("Version") ?: throw DockerException("The CLI printed no version")
    return EngineInfo(
        flavor = if (podman) EngineFlavor.PODMAN else EngineFlavor.DOCKER,
        serverVersion = serverVersion,
        clientVersion = client?.text("Version"),
        platform = server?.field("Platform")?.text("Name"),
        // Docker's CLI names its context here since version 20; Podman has none.
        context = if (podman) null else client?.text("Context"),
    )
}

/**
 * The details in the output of `container inspect <id>` (an array of one object, Docker's and Podman's alike).
 *
 * @throws DockerException if the output holds no container.
 */
internal fun parseContainerDetails(text: String): ContainerDetails {
    val node = jsonObjects(text).firstOrNull() ?: throw DockerException("The CLI printed no container")
    val config = node.field("Config")
    val state = node.field("State")
    val containerState = ContainerState.of(state?.text("Status"))
    val health = state?.field("Health", "Healthcheck")
    return ContainerDetails(
        id = node.text("Id", "ID") ?: "",
        name = node.text("Name")?.removePrefix("/") ?: "",
        image = config?.text("Image") ?: node.text("ImageName") ?: node.text("Image") ?: "",
        imageId = node.text("Image"),
        state = containerState,
        createdAt = parseTime(node.text("Created")),
        startedAt = parseTime(state?.text("StartedAt")),
        finishedAt = parseTime(state?.text("FinishedAt")),
        exitCode =
            state?.long("ExitCode")?.toInt()?.takeIf {
                containerState == ContainerState.EXITED || containerState == ContainerState.DEAD
            },
        restartCount = node.long("RestartCount")?.toInt() ?: 0,
        health = health?.text("Status"),
        command = config?.command("Cmd") ?: emptyList(),
        entrypoint = config?.command("Entrypoint") ?: emptyList(),
        env = config?.texts("Env")?.let(::environment) ?: emptyList(),
        labels = config?.labels("Labels") ?: emptyMap(),
        mounts = node.field("Mounts")?.values()?.mapNotNull(::mount) ?: emptyList(),
        networks = networks(node.field("NetworkSettings")?.field("Networks")),
        ports = inspectPorts(node.field("NetworkSettings")?.field("Ports")),
    )
}

/** `KEY=VALUE` entries in their order; a value may contain `=`, an entry without one has an empty value. */
private fun environment(entries: List<String>): List<EnvVariable> =
    entries.map { entry ->
        val equals = entry.indexOf('=')
        if (equals < 0) EnvVariable(entry, "") else EnvVariable(entry.substring(0, equals), entry.substring(equals + 1))
    }

private fun mount(node: JsonNode): Mount? {
    val destination = node.text("Destination") ?: return null
    return Mount(
        type = node.text("Type") ?: "",
        name = node.text("Name"),
        source = node.text("Source"),
        destination = destination,
        readWrite = node.bool("RW") ?: true,
    )
}

private fun networks(node: JsonNode?): List<NetworkAttachment> =
    node?.properties()?.map { (name, network) ->
        NetworkAttachment(
            name = name,
            ipAddress = network.text("IPAddress"),
            aliases = network.texts("Aliases"),
        )
    } ?: emptyList()

/** `{"80/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8080"}], "9000/tcp": null}`; unpublished ports once. */
private fun inspectPorts(node: JsonNode?): List<PortMapping> =
    node?.properties()?.flatMap { (key, bindings) ->
        val containerPort = key.substringBefore('/').toIntOrNull() ?: return@flatMap emptyList()
        val protocol = key.substringAfter('/', "tcp")
        val published =
            bindings?.takeIf { it.isArray }?.values()?.map { binding ->
                PortMapping(binding.text("HostIp"), binding.long("HostPort")?.toInt(), containerPort, protocol)
            } ?: emptyList()
        published.ifEmpty { listOf(PortMapping(null, null, containerPort, protocol)) }
    } ?: emptyList()

/** [text], the output of `inspect`, pretty-printed with two spaces; an array of one object unwrapped. */
internal fun prettyInspect(text: String): String {
    val node =
        try {
            dockerJson.readTree(text)
        } catch (_: tools.jackson.core.JacksonException) {
            return text.trim()
        }
    val shown = if (node.isArray && node.size() == 1) node.get(0) else node
    return prettyJson.writer().writeValueAsString(shown)
}
