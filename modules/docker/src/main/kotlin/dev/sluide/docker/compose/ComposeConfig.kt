package dev.sluide.docker.compose

import dev.sluide.docker.cli.ComposeConfigOutput
import dev.sluide.docker.cli.DockerException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private val mapper = JsonMapper()

/**
 * One service of a compose project as `docker compose config` resolves it.
 *
 * @property image the image, if the service names one.
 * @property hasBuild whether it has a `build` section.
 * @property ports display strings in the order of the file: `8080:80/tcp`, `127.0.0.1:8443:443/tcp`, `5432/tcp`
 *   (not published).
 * @property profiles the profiles it belongs to; empty = always started.
 * @property dependsOn the names of the services it depends on.
 */
data class ComposeService(
    val name: String,
    val image: String?,
    val hasBuild: Boolean,
    val ports: List<String>,
    val profiles: Set<String>,
    val dependsOn: List<String>,
)

/**
 * A resolved compose project.
 *
 * @property name the project name (what the containers' `com.docker.compose.project` label holds).
 * @property services as `config` prints them, which is alphabetical, not the order of the file (use [serviceLines]
 *   for that); all of them, because the listing enables every profile (see [ComposeCommands.baseArgs]).
 * @property profiles every profile any service names, sorted.
 * @property unresolvedVariables the `${VAR}`s without a value (compose warns and uses a blank string).
 * @property warnings the other warnings compose printed, as text lines (e.g. the obsolete `version` attribute).
 */
data class ComposeConfig(
    val name: String,
    val services: List<ComposeService>,
    val profiles: Set<String>,
    val unresolvedVariables: Set<String>,
    val warnings: List<String>,
)

/**
 * Reads the output of `docker compose config --format json` ([output]): stdout is the project, stderr its warnings.
 *
 * @throws DockerException if stdout is not the JSON of a project (with a message to show the user).
 */
fun parseComposeConfig(output: ComposeConfigOutput): ComposeConfig {
    val root =
        try {
            mapper.readTree(output.json)
        } catch (e: JacksonException) {
            throw DockerException("The output of docker compose config is not JSON: ${e.originalMessage}", cause = e)
        }
    if (root == null || !root.isObject) throw DockerException("The output of docker compose config is not a project")
    val services =
        root
            .get("services")
            ?.takeIf { it.isObject }
            ?.properties()
            ?.map { (name, node) -> service(name, node) }
            .orEmpty()
    val (unresolved, other) = parseComposeWarnings(output.warnings)
    return ComposeConfig(
        name =
            root
                .get("name")
                ?.takeIf { it.isString }
                ?.stringValue()
                .orEmpty(),
        services = services,
        profiles = services.flatMap { it.profiles }.toSortedSet(),
        unresolvedVariables = unresolved,
        warnings = other,
    )
}

/**
 * Splits compose's stderr into the names of unresolved variables (`The "FOO" variable is not set. Defaulting to a
 * blank string.`) and the other warnings as text lines: of the logfmt lines (`time=… level=warning msg="…"`) the
 * message, other lines as they are; blank lines are dropped.
 */
internal fun parseComposeWarnings(stderr: String): Pair<Set<String>, List<String>> {
    val unresolved = linkedSetOf<String>()
    val others = mutableListOf<String>()
    for (line in stderr.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
        val message =
            LOGFMT_MESSAGE
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.replace(ESCAPE) { it.groupValues[1] } ?: line
        val variable = UNSET_VARIABLE.find(message)
        if (variable != null) unresolved += variable.groupValues[1] else others += message
    }
    return unresolved to others
}

private val LOGFMT_MESSAGE = Regex("""^time="[^"]*" level=\w+ msg="((?:[^"\\]|\\.)*)"$""")
private val ESCAPE = Regex("""\\(.)""")
private val UNSET_VARIABLE = Regex("""^The "([^"]+)" variable is not set""")

private fun service(
    name: String,
    node: JsonNode,
): ComposeService =
    ComposeService(
        name = name,
        image = node.get("image")?.takeIf { it.isString }?.stringValue(),
        hasBuild = node.get("build")?.let { !it.isNull } ?: false,
        ports = arrayValues(node.get("ports")).mapNotNull(::port),
        profiles = arrayValues(node.get("profiles")).mapNotNull { it.asString(null) }.toSet(),
        dependsOn = dependencies(node.get("depends_on")),
    )

/** The elements of [node] if it is an array, else none. */
private fun arrayValues(node: JsonNode?): List<JsonNode> =
    if (node != null &&
        node.isArray
    ) {
        node.values().toList()
    } else {
        emptyList()
    }

/** `[host_ip:]published:target/protocol`, or `target/protocol` for a port that is not published. */
private fun port(node: JsonNode): String? {
    if (node.isString) return node.stringValue()
    val target = node.get("target")?.asString(null) ?: return null
    val protocol = node.get("protocol")?.asString(null) ?: "tcp"
    val published = node.get("published")?.asString(null)?.takeIf { it.isNotEmpty() }
    val hostIp = node.get("host_ip")?.asString(null)?.takeIf { it.isNotEmpty() }
    val host = if (published == null) "" else listOfNotNull(hostIp, published).joinToString(":") + ":"
    return "$host$target/$protocol"
}

/** `depends_on` is a map after `config`, a list in hand-written input; both give the names. */
private fun dependencies(node: JsonNode?): List<String> =
    when {
        node == null -> emptyList()
        node.isObject -> node.propertyNames().toList()
        node.isArray -> node.values().mapNotNull { it.asString(null) }
        else -> emptyList()
    }
