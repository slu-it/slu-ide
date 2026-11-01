package dev.sluide.docker.model

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The containers of one Compose project.
 *
 * @property workingDir the folder the project was started from, if its containers tell it.
 * @property local whether [workingDir] lies inside the open project folder.
 * @property containers sorted by service, then name.
 */
data class ComposeProject(
    val name: String,
    val workingDir: String?,
    val local: Boolean,
    val containers: List<Container>,
)

/**
 * The containers as the Docker tab shows them: Compose [projects] first, then [standalone] containers.
 *
 * @property projects the projects inside the open project folder first, then the others; each part by name.
 * @property standalone the containers of no Compose project, by name.
 */
data class ContainerTree(
    val projects: List<ComposeProject>,
    val standalone: List<Container>,
)

private val byName: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder())

/**
 * Groups [containers] by their Compose project (label `com.docker.compose.project`) and sorts them for the tree.
 * A project counts as local when its working folder lies inside [projectFolder] (compared as normalised paths, without
 * reading the file system).
 */
fun containerTree(
    containers: List<Container>,
    projectFolder: Path?,
): ContainerTree {
    val (composed, standalone) = containers.partition { it.composeProject != null }
    val projects =
        composed
            .groupBy { it.composeProject!! }
            .map { (name, members) ->
                val workingDir = members.firstNotNullOfOrNull { it.composeWorkingDir }
                ComposeProject(
                    name = name,
                    workingDir = workingDir,
                    local = isInside(workingDir, projectFolder),
                    containers =
                        members.sortedWith(
                            compareBy<Container, String>(byName) { it.composeService ?: "" }.thenBy(byName) { it.name },
                        ),
                )
            }.sortedWith(compareBy<ComposeProject> { !it.local }.thenBy(byName) { it.name })
    return ContainerTree(projects, standalone.sortedWith(compareBy(byName) { it.name }))
}

private fun isInside(
    folder: String?,
    projectFolder: Path?,
): Boolean {
    if (folder == null || projectFolder == null) return false
    return try {
        Path.of(folder).normalize().startsWith(projectFolder.normalize())
    } catch (_: InvalidPathException) {
        false
    }
}
