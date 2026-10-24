package dev.sluide.gradle.view

import dev.sluide.gradle.tasks.GradleProject
import dev.sluide.gradle.tasks.GradleTask
import dev.sluide.gradle.tasks.OTHER_GROUP
import dev.sluide.gradle.tasks.groups

/** A project of the task tree with its groups; [label] is what its row shows. */
internal data class TreeProject(
    val path: String,
    val label: String,
    val expanded: Boolean,
    val groups: List<TreeGroup>,
)

/** A group of tasks of a project in the task tree. */
internal data class TreeGroup(
    val name: String,
    val expanded: Boolean,
    val tasks: List<GradleTask>,
)

/** What a project's row shows: its path (`:app:core`); the root project its name and `(:)`. */
internal fun projectLabel(project: GradleProject): String =
    if (project.path == ":") "${project.name} (:)" else project.path

/**
 * What Gradle gets for [tasks]: a task of [ownProject] by its name (Gradle matches it in the project of the working
 * folder and its subprojects), any other task by its path (only that task); all paths without an [ownProject], and for a name Gradle would read
 * as an option (`-x`).
 */
internal fun taskArguments(
    tasks: List<GradleTask>,
    ownProject: String?,
): List<String> = tasks.map { if (isOwn(it, ownProject)) it.name else it.path }

/** Whether [task] is a task of [ownProject]: its path is the project's path and the name (`:` + name for the root). */
internal fun isOwn(
    task: GradleTask,
    ownProject: String?,
): Boolean =
    ownProject != null &&
        !task.name.startsWith("-") &&
        task.path == (if (ownProject == ":") ":" else "$ownProject:") + task.name

/** A task row's tooltip: what Gradle gets and its scope, then the description. */
internal fun taskTooltip(
    task: GradleTask,
    ownProject: String?,
): String {
    val scope = if (isOwn(task, ownProject)) "this project and its subprojects" else "only this task"
    return listOfNotNull("${taskArguments(listOf(task), ownProject).single()} ($scope)", task.description)
        .joinToString("\n")
}

/** The project of the view's build file among [projects] (a subtree): the one all others are below of. */
internal fun ownProject(projects: List<GradleProject>): String? = projects.minByOrNull { depth(it.path) }?.path

/**
 * The task tree of [projects], project → group → task, projects by path (parents first), groups as [groups] orders
 * them. Without a [filter] only [ownProject] is expanded, with its groups except [OTHER_GROUP]. A filter keeps the
 * tasks whose name contains it (ignoring case) with their group and project, all expanded, and drops the rest.
 */
internal fun taskTree(
    projects: List<GradleProject>,
    ownProject: String?,
    filter: String,
): List<TreeProject> {
    val query = filter.trim()
    val filtering = query.isNotEmpty()
    return projects.sortedWith(compareBy(PATH_ORDER) { it.path }).mapNotNull { project ->
        val own = project.path == ownProject
        val groups =
            groups(project).mapNotNull { (name, tasks) ->
                val shown = if (filtering) tasks.filter { it.name.contains(query, ignoreCase = true) } else tasks
                shown.takeIf { it.isNotEmpty() }?.let {
                    TreeGroup(name, expanded = filtering || (own && name != OTHER_GROUP), tasks = it)
                }
            }
        if (filtering && groups.isEmpty()) {
            null
        } else {
            TreeProject(project.path, projectLabel(project), expanded = filtering || own, groups = groups)
        }
    }
}

private fun depth(path: String) = if (path == ":") 0 else path.count { it == ':' }

/** Project paths by their parts, so `:app:core` follows `:app` before `:app-x`. */
private val PATH_ORDER =
    Comparator<String> { a, b ->
        val left = a.split(':').filter { it.isNotEmpty() }
        val right = b.split(':').filter { it.isNotEmpty() }
        left
            .zip(right)
            .map { (x, y) -> x.compareTo(y) }
            .firstOrNull { it != 0 } ?: left.size.compareTo(right.size)
    }
