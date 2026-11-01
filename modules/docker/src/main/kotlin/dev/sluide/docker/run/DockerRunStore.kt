package dev.sluide.docker.run

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(DockerRunStore::class.java.name)

/** The field of the project data (or the shared file) that holds the last run values, keyed by image repository. */
internal const val RUNS_FIELD = "dockerRuns"

/**
 * The values last used in the Run Container dialog, per image repository ([repositoryOf]), kept in the project's data
 * or, without a project, in the shared file. Untagged images (run by id) are not remembered. Only values that differ
 * from the defaults are written; an entry without any is dropped, and so is the field. A small synchronized JSON file:
 * called on any thread. A broken store never throws: unusable content gives the defaults (single invalid values are
 * dropped) and is logged.
 */
@Component
class DockerRunStore(
    private val store: ProjectDataStore,
) {
    /** The options to start the dialog for [reference] with: the stored ones, else the defaults; the image is [reference]. */
    internal fun load(
        reference: String,
        projectFolder: Path?,
    ): RunOptions {
        val repository = repositoryOf(reference) ?: return RunOptions(reference)
        return try {
            val root = if (projectFolder != null) store.read(projectFolder) else store.readShared()
            ((root?.get(RUNS_FIELD) as? ObjectNode)?.get(repository) as? ObjectNode)?.let { read(reference, it) }
                ?: RunOptions(reference)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot read the run values of $repository, using the defaults", e)
            RunOptions(reference)
        }
    }

    /** Stores [options] for the repository of its image; `true` if saved (nothing for an image id; failures are logged). */
    internal fun save(
        options: RunOptions,
        projectFolder: Path?,
    ): Boolean {
        val repository = repositoryOf(options.image) ?: return false
        val node = write(options.withoutBlankRows())
        val edit = { root: ObjectNode -> change(root, repository, node) }
        return try {
            if (projectFolder != null) store.update(projectFolder, edit) else store.updateShared(edit)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot store the run values of $repository", e)
            false
        }
    }

    private fun change(
        root: ObjectNode,
        repository: String,
        node: ObjectNode,
    ) {
        val runs = (root.get(RUNS_FIELD) as? ObjectNode) ?: mapper.createObjectNode()
        if (node.isEmpty) runs.remove(repository) else runs.set(repository, node)
        if (runs.isEmpty) root.remove(RUNS_FIELD) else root.set(RUNS_FIELD, runs)
    }
}

internal fun write(options: RunOptions): ObjectNode {
    val defaults = RunOptions(options.image)
    val node = mapper.createObjectNode()
    if (options.name.isNotEmpty()) node.put("name", options.name)
    if (options.ports.isNotEmpty()) node.putArray("ports").apply { options.ports.forEach(::add) }
    if (options.environment.isNotEmpty()) {
        node
            .putArray(
                "env",
            ).apply { options.environment.forEach { addObject().put("name", it.name).put("value", it.value) } }
    }
    if (options.volumes.isNotEmpty()) {
        node.putArray("volumes").apply {
            options.volumes.forEach {
                addObject().put("source", it.source).put("target", it.target).put("readOnly", it.readOnly)
            }
        }
    }
    options.network?.let { node.put("network", it) }
    if (options.command.isNotEmpty()) node.put("command", options.command)
    if (options.detached != defaults.detached) node.put("detached", options.detached)
    if (options.removeWhenStopped != defaults.removeWhenStopped) node.put("rm", options.removeWhenStopped)
    return node
}

internal fun read(
    reference: String,
    node: ObjectNode,
): RunOptions {
    val defaults = RunOptions(reference)
    return RunOptions(
        image = reference,
        name = text(node.get("name"))?.takeIf(::isValidContainerName) ?: "",
        ports = array(node.get("ports")).mapNotNull { text(it) }.filter(::isValidPort),
        environment = array(node.get("env")).mapNotNull(::variable),
        volumes = array(node.get("volumes")).mapNotNull(::volume),
        network = text(node.get("network"))?.takeIf { it.isNotBlank() && !it.startsWith("-") },
        command = text(node.get("command"))?.takeIf { validate(RunOptions(reference, command = it)) == null } ?: "",
        detached = node.get("detached")?.takeIf { it.isBoolean }?.booleanValue() ?: defaults.detached,
        removeWhenStopped = node.get("rm")?.takeIf { it.isBoolean }?.booleanValue() ?: defaults.removeWhenStopped,
    )
}

private fun text(node: JsonNode?): String? = node?.takeIf { it.isString }?.stringValue()?.takeIf { '\u0000' !in it }

private fun array(node: JsonNode?): List<JsonNode> =
    if (node != null &&
        node.isArray
    ) {
        node.values().toList()
    } else {
        emptyList()
    }

private fun variable(node: JsonNode): RunEnvVar? {
    val name = text(node.get("name"))?.takeIf(::isValidEnvName) ?: return null
    return RunEnvVar(name, text(node.get("value")) ?: return null)
}

private fun volume(node: JsonNode): RunVolume? {
    val volume =
        RunVolume(
            text(node.get("source")) ?: return null,
            text(node.get("target")) ?: return null,
            node.get("readOnly")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        )
    return volume.takeIf { validate(RunOptions("x", volumes = listOf(it))) == null }
}
