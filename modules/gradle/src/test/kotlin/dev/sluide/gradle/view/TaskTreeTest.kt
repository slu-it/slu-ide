package dev.sluide.gradle.view

import dev.sluide.gradle.tasks.GradleProject
import dev.sluide.gradle.tasks.GradleTask
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskTreeTest {
    private fun project(
        path: String,
        vararg tasks: Pair<String, String?>,
    ) = GradleProject(
        path,
        if (path == ":") "fx" else path.substringAfterLast(':'),
        Path.of("/fx"),
        null,
        tasks.map { (name, group) -> GradleTask(name, if (path == ":") ":$name" else "$path:$name", group, null) },
    )

    private val root = project(":", "build" to "build", "help" to "help", "hello" to null)
    private val app = project(":app", "test" to "verification", "assemble" to "build", "appTask" to null)
    private val core = project(":app:core", "coreTask" to null)
    private val appX = project(":app-x", "x" to "build")

    /** The tree as text: `+` expanded, `-` collapsed, two spaces per level. */
    private fun render(entries: List<TreeProject>): List<String> =
        entries.flatMap { project ->
            listOf((if (project.expanded) "+" else "-") + project.label) +
                project.groups.flatMap { group ->
                    listOf("  " + (if (group.expanded) "+" else "-") + group.name) +
                        group.tasks.map { "    " + it.path }
                }
        }

    @Test
    fun `projects by path, own project and its named groups expanded, other collapsed and last`() {
        assertEquals(
            listOf(
                "+fx (:)",
                "  +build",
                "    :build",
                "  +help",
                "    :help",
                "  -other",
                "    :hello",
                "-:app",
                "  -build",
                "    :app:assemble",
                "  -verification",
                "    :app:test",
                "  -other",
                "    :app:appTask",
                "-:app:core",
                "  -other",
                "    :app:core:coreTask",
                "-:app-x",
                "  -build",
                "    :app-x:x",
            ),
            render(taskTree(listOf(core, appX, app, root), ":", "")),
        )
    }

    @Test
    fun `a subproject's view expands that project only`() {
        assertEquals(
            listOf("+:app", "  +build", "  +verification", "  -other", "-:app:core", "  -other"),
            render(taskTree(listOf(app, core), ownProject(listOf(core, app)), "")).filterNot { it.startsWith("    ") },
        )
    }

    @Test
    fun `the filter keeps matching tasks by name ignoring case, with their parents, all expanded`() {
        assertEquals(
            listOf("+:app", "  +other", "    :app:appTask", "+:app:core", "  +other", "    :app:core:coreTask"),
            render(taskTree(listOf(root, app, core), ":", " TASK ")),
        )
        assertEquals(emptyList(), taskTree(listOf(root, app, core), ":", "nothing"))
    }

    @Test
    fun `the root project is labelled with its name, others with their path`() {
        assertEquals(listOf("fx (:)", ":app"), listOf(projectLabel(root), projectLabel(app)))
    }

    @Test
    fun `a project without tasks stays without a filter`() {
        assertEquals(listOf("-:empty"), render(taskTree(listOf(project(":empty")), ":", "")))
    }
}
