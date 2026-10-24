package dev.sluide.gradle.tasks

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.nio.file.Path

/** A Gradle project of the build: [path] `:app:core` (`:` for the root), its [folder] and its tasks. */
data class GradleProject(
    val path: String,
    val name: String,
    val folder: Path,
    val buildFile: Path?,
    val tasks: List<GradleTask>,
)

/** A task of a project: [path] is absolute (`:app:build`). */
data class GradleTask(
    val name: String,
    val path: String,
    val group: String?,
    val description: String?,
)

/** The group shown for tasks without one. */
const val OTHER_GROUP = "other"

/** The projects and tasks of a build, read from the JSON of the init script (ADR 0085). */
class TaskModel(
    val rootFolder: Path,
    val projects: List<GradleProject>,
) {
    /**
     * The project whose build file is [buildFile] and every project below it (`:app` then `:app:core`; the root all);
     * all projects if no project has that build file. Reads the file system (links are resolved), so not on the
     * JavaFX thread; the projects' paths are resolved once.
     */
    fun subtreeOf(buildFile: Path): List<GradleProject> {
        val file = realPath(buildFile)
        val own = projects.firstOrNull { realBuildFiles[it] == file } ?: return projects
        return projects.filter { it.path == own.path || isBelow(it.path, own.path) }
    }

    private val realBuildFiles: Map<GradleProject, Path?> by lazy {
        projects.associateWith { it.buildFile?.let(::realPath) }
    }

    /** The path with links resolved (Gradle reports real paths), or normalized if it does not exist. */
    private fun realPath(path: Path): Path =
        try {
            path.toRealPath()
        } catch (_: IOException) {
            path.toAbsolutePath().normalize()
        }

    private fun isBelow(
        path: String,
        ancestor: String,
    ) = if (ancestor == ":") path != ":" else path.startsWith("$ancestor:")

    companion object {
        private const val VERSION = 1
        private val mapper = JsonMapper.builder().build()

        /** Reads [json]; throws [IllegalArgumentException] saying what is wrong if it is not a task list. */
        fun parse(json: String): TaskModel {
            val root =
                try {
                    mapper.readTree(json)
                } catch (e: JacksonException) {
                    throw IllegalArgumentException("The task list is not valid JSON: ${e.originalMessage}", e)
                }
            require(root.isObject) { "The task list is not a JSON object" }
            val version = root.path("version")
            require(version.isNumber && version.asInt() == VERSION) {
                "Unsupported task list version: ${if (version.isMissingNode) "none" else version.toString()}"
            }
            val projects = root.path("projects")
            require(projects.isArray) { "The task list has no projects" }
            return TaskModel(
                Path.of(text(root, "rootFolder") ?: throw IllegalArgumentException("The task list has no rootFolder")),
                projects.values().map(::project),
            )
        }

        private fun project(node: JsonNode): GradleProject {
            fun required(key: String) =
                text(node, key) ?: throw IllegalArgumentException("A project of the task list has no $key")
            return GradleProject(
                path = required("path"),
                name = required("name"),
                folder = Path.of(required("folder")),
                buildFile = text(node, "buildFile")?.let { Path.of(it) },
                tasks = node.path("tasks").values().map(::task),
            )
        }

        private fun task(node: JsonNode): GradleTask {
            fun required(key: String) =
                text(node, key) ?: throw IllegalArgumentException("A task of the task list has no $key")
            return GradleTask(required("name"), required("path"), text(node, "group"), text(node, "description"))
        }

        private fun text(
            node: JsonNode,
            key: String,
        ): String? = node.path(key).takeIf { it.isString }?.asString()
    }
}

/**
 * The tasks of [project] by group for the tree: named groups alphabetically, [OTHER_GROUP] last (also for tasks of a
 * group of that name), tasks by name; groups without a task do not appear.
 */
fun groups(project: GradleProject): Map<String, List<GradleTask>> {
    val byGroup = project.tasks.groupBy { it.group?.takeIf { group -> group.isNotBlank() } ?: OTHER_GROUP }
    val order =
        compareBy<String> {
            it == OTHER_GROUP
        }.thenComparing(String.CASE_INSENSITIVE_ORDER).thenComparing(naturalOrder())
    return byGroup.toSortedMap(order).mapValues { (_, tasks) -> tasks.sortedBy { it.name } }
}
