package dev.sluide.docker.compose

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(ComposeStore::class.java.name)

/** The field of a file's project data that holds its [ComposeOptions] (a JSON object). */
internal const val COMPOSE_FIELD = "compose"

/**
 * The [ComposeOptions] of every compose file, kept in the file's project data ([ProjectDataStore.updateFileData], field
 * `compose`), in the project's file or, outside a project, the shared file. Only values that differ from the defaults
 * are written; the field is dropped when nothing is left. Like the run-argument histories it is a small, synchronized
 * JSON file: called on whichever thread the caller is on (the FX thread is fine). A broken store never throws:
 * unknown or invalid content gives the defaults (invalid single values are dropped) and is logged.
 */
@Component
class ComposeStore(
    private val store: ProjectDataStore,
) {
    /** The options of [file] in the project in [projectFolder]; the defaults if none are stored or they are unusable. */
    fun load(
        file: Path,
        projectFolder: Path?,
    ): ComposeOptions =
        try {
            (store.fileData(file, projectFolder)?.get(COMPOSE_FIELD) as? ObjectNode)?.let(::read) ?: ComposeOptions()
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot read the compose options of $file, using the defaults", e)
            ComposeOptions()
        }

    /** Stores [options] for [file]; `true` if they were saved (a failure is logged, never thrown). */
    fun save(
        file: Path,
        projectFolder: Path?,
        options: ComposeOptions,
    ): Boolean =
        try {
            store.updateFileData(file, projectFolder) { data ->
                val node = write(options)
                if (node.isEmpty) data.remove(COMPOSE_FIELD) else data.set(COMPOSE_FIELD, node)
            }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot store the compose options of $file", e)
            false
        }
}

internal fun write(options: ComposeOptions): ObjectNode {
    val defaults = ComposeOptions()
    val node = mapper.createObjectNode()
    if (options.detached != defaults.detached) node.put("detached", options.detached)
    if (options.profiles.isNotEmpty()) node.putArray("profiles").apply { options.profiles.sorted().forEach(::add) }
    if (options.build) node.put("build", true)
    if (options.forceRecreate) node.put("forceRecreate", true)
    if (options.removeOrphans) node.put("removeOrphans", true)
    options.projectName?.let { node.put("projectName", it) }
    options.envFiles?.let { files -> node.putArray("envFiles").apply { files.forEach(::add) } }
    if (options.variables.isNotEmpty()) {
        node.putArray("variables").apply {
            options.variables.forEach { addObject().put("name", it.name).put("value", it.value) }
        }
    }
    return node
}

internal fun read(node: ObjectNode): ComposeOptions {
    val defaults = ComposeOptions()
    return ComposeOptions(
        detached = node.get("detached")?.takeIf { it.isBoolean }?.booleanValue() ?: defaults.detached,
        profiles = strings(node.get("profiles")).filter(::isValidProfileName).toSet(),
        build = node.get("build")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        forceRecreate = node.get("forceRecreate")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        removeOrphans = node.get("removeOrphans")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        projectName =
            node
                .get("projectName")
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf(::isValidProjectName),
        envFiles =
            node
                .get("envFiles")
                ?.takeIf { it.isArray }
                ?.let { strings(it).filter { path -> path.isNotBlank() && '\u0000' !in path } },
        variables =
            (node.get("variables")?.takeIf { it.isArray })
                ?.values()
                ?.mapNotNull(::variable)
                .orEmpty(),
    )
}

private fun strings(node: JsonNode?): List<String> =
    if (node == null || !node.isArray) emptyList() else node.values().filter { it.isString }.map { it.stringValue() }

private fun variable(node: JsonNode): ComposeVariable? {
    val name =
        node
            .get("name")
            ?.takeIf { it.isString }
            ?.stringValue()
            ?.takeIf(::isValidVariableName) ?: return null
    val value =
        node
            .get("value")
            ?.takeIf { it.isString }
            ?.stringValue()
            ?.takeIf { '\u0000' !in it } ?: return null
    return ComposeVariable(name, value)
}
