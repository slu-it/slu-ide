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

    private fun task(
        project: GradleProject,
        name: String,
    ) = project.tasks.single { it.name == name }

    @Test
    fun `tasks of the own root project are passed by name, those of child projects by path`() {
        val tasks = listOf(task(root, "build"), task(app, "test"), task(core, "coreTask"))
        assertEquals(listOf("build", ":app:test", ":app:core:coreTask"), taskArguments(tasks, ":"))
    }

    @Test
    fun `tasks of an own subproject are passed by name, those of its children and others by path`() {
        val tasks = listOf(task(app, "test"), task(core, "coreTask"), task(appX, "x"), task(root, "help"))
        assertEquals(listOf("test", ":app:core:coreTask", ":app-x:x", ":help"), taskArguments(tasks, ":app"))
    }

    @Test
    fun `without an own project every task is passed by path, in the order given`() {
        val tasks = listOf(task(app, "test"), task(root, "build"), task(core, "coreTask"))
        assertEquals(listOf(":app:test", ":build", ":app:core:coreTask"), taskArguments(tasks, null))
        assertEquals(emptyList(), taskArguments(emptyList(), ":"))
    }

    @Test
    fun `a task belongs to its own project only, not to one whose path it starts with`() {
        assertEquals(true, isOwn(task(app, "test"), ":app"))
        assertEquals(false, isOwn(task(core, "coreTask"), ":app"))
        assertEquals(false, isOwn(task(app, "test"), ":"))
    }

    @Test
    fun `a task name that would be read as an option keeps its path`() {
        val dash = GradleTask("-x", ":-x", null, null)
        assertEquals(listOf(":-x"), taskArguments(listOf(dash), ":"))
    }

    @Test
    fun `the tooltip says what Gradle gets and its scope, then the description`() {
        val described = GradleTask("clean", ":clean", null, "Deletes the build directory.")
        assertEquals(
            "clean (this project and its subprojects)\nDeletes the build directory.",
            taskTooltip(described, ":"),
        )
        assertEquals(":clean (only this task)\nDeletes the build directory.", taskTooltip(described, ":app"))
        assertEquals(":app:test (only this task)", taskTooltip(task(app, "test"), null))
    }
}
