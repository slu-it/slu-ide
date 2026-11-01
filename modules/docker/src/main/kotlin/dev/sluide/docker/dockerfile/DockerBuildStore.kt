package dev.sluide.docker.dockerfile

import dev.sluide.projectdata.ProjectDataStore
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path

private val mapper = JsonMapper()
private val log = System.getLogger(DockerBuildStore::class.java.name)

/** The field of a file's project data that holds its [BuildOptions] (a JSON object). */
internal const val DOCKER_BUILD_FIELD = "dockerBuild"

/**
 * The [BuildOptions] of every Dockerfile, kept in the file's project data ([ProjectDataStore.updateFileData], field
 * `dockerBuild`), in the project's file or, outside a project, the shared file, like the compose options. Only values
 * that differ from the defaults are written; the field is dropped when nothing is left. Called on whichever thread the
 * caller is on (the FX thread is fine). A broken store never throws: unknown or invalid content gives the defaults
 * (invalid single values are dropped) and is logged.
 */
@Component
class DockerBuildStore(
    private val store: ProjectDataStore,
) {
    /** The options of [file] in the project in [projectFolder]; the defaults if none are stored or they are unusable. */
    internal fun load(
        file: Path,
        projectFolder: Path?,
    ): BuildOptions =
        try {
            (store.fileData(file, projectFolder)?.get(DOCKER_BUILD_FIELD) as? ObjectNode)?.let(::readBuildOptions)
                ?: BuildOptions()
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot read the build options of $file, using the defaults", e)
            BuildOptions()
        }

    /** Stores [options] for [file]; `true` if they were saved (a failure is logged, never thrown). */
    internal fun save(
        file: Path,
        projectFolder: Path?,
        options: BuildOptions,
    ): Boolean =
        try {
            store.updateFileData(file, projectFolder) { data ->
                val node = writeBuildOptions(options)
                if (node.isEmpty) data.remove(DOCKER_BUILD_FIELD) else data.set(DOCKER_BUILD_FIELD, node)
            }
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "Cannot store the build options of $file", e)
            false
        }
}

private fun writeBuildOptions(options: BuildOptions): ObjectNode {
    val node = mapper.createObjectNode()
    if (options.tags.isNotEmpty()) node.put("tags", options.tags)
    if (options.history.isNotEmpty()) node.putArray("history").apply { options.history.forEach(::add) }
    if (options.context.isNotEmpty()) node.put("context", options.context)
    if (options.target.isNotEmpty()) node.put("target", options.target)
    if (options.buildArgs.isNotEmpty()) {
        node.putObject("buildArgs").apply {
            options.buildArgs.toSortedMap().forEach { (name, value) ->
                put(name, value)
            }
        }
    }
    if (options.noCache) node.put("noCache", true)
    if (options.pull) node.put("pull", true)
    if (options.platform.isNotEmpty()) node.put("platform", options.platform)
    return node
}

private fun readBuildOptions(node: ObjectNode): BuildOptions =
    BuildOptions(
        tags = text(node.get("tags")),
        history =
            node
                .get("history")
                ?.takeIf { it.isArray }
                ?.values()
                ?.filter { it.isString }
                ?.map { it.stringValue() }
                ?.filter { it.isNotBlank() && '\u0000' !in it }
                ?.distinct()
                ?.take(MAX_TAG_HISTORY)
                .orEmpty(),
        context = text(node.get("context")),
        target = text(node.get("target")),
        buildArgs =
            (node.get("buildArgs") as? ObjectNode)
                ?.properties()
                ?.filter { (name, value) -> name.isNotEmpty() && value.isString && '\u0000' !in value.stringValue() }
                ?.associate { (name, value) -> name to value.stringValue() }
                .orEmpty(),
        noCache = node.get("noCache")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        pull = node.get("pull")?.takeIf { it.isBoolean }?.booleanValue() ?: false,
        platform = text(node.get("platform")),
    )

/** The string of [node]; empty if it is none or holds a NUL character. */
private fun text(node: JsonNode?): String =
    node
        ?.takeIf { it.isString }
        ?.stringValue()
        ?.takeIf { '\u0000' !in it }
        .orEmpty()
