package dev.sluide.gradle.tasks

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fixture is the trimmed output of the init script run by Gradle 9.8.1 on a small multi-project build. */
class TaskModelTest {
    private val model =
        TaskModel.parse(
            checkNotNull(javaClass.getResourceAsStream("task-list.json")).use { it.readBytes().decodeToString() },
        )

    private fun paths(projects: List<GradleProject>) = projects.map { it.path }

    private fun paths(subtree: ProjectSubtree) = paths(subtree.projects)

    @Test
    fun `the task list of the init script is read`() {
        assertEquals(Path.of("/work/fx"), model.rootFolder)
        assertEquals(listOf(":", ":app", ":app:core", ":lib"), paths(model.projects))
        val root = model.projects.first()
        assertEquals("fx", root.name)
        assertEquals(Path.of("/work/fx/build.gradle"), root.buildFile)
        assertEquals(GradleTask("hello", ":hello", "demo", "Says \"hello\""), root.tasks.first { it.name == "hello" })
        assertEquals(Path.of("/work/fx/lib/build.gradle.kts"), model.projects.last().buildFile)
        assertNull(
            model.projects[2]
                .tasks
                .first { it.name == "coreTask" }
                .group,
        )
    }

    @Test
    fun `a project without a build file has none`() {
        val parsed =
            TaskModel.parse(
                """{"version":1,"rootFolder":"/r","projects":[{"path":":","name":"r","folder":"/r","buildFile":null,"tasks":[]}]}""",
            )
        assertNull(parsed.projects.single().buildFile)
    }

    @Test
    fun `the root build file gets every project`() {
        assertEquals(paths(model.projects), paths(model.subtreeOf(Path.of("/work/fx/build.gradle"))))
        assertEquals(":", model.subtreeOf(Path.of("/work/fx/build.gradle")).own)
    }

    @Test
    fun `a subproject build file gets the project and the projects below it`() {
        assertEquals(listOf(":app", ":app:core"), paths(model.subtreeOf(Path.of("/work/fx/app/build.gradle"))))
        assertEquals(listOf(":app:core"), paths(model.subtreeOf(Path.of("/work/fx/app/core/build.gradle"))))
        assertEquals(listOf(":lib"), paths(model.subtreeOf(Path.of("/work/fx/lib/build.gradle.kts"))))
        assertEquals(":app", model.subtreeOf(Path.of("/work/fx/app/build.gradle")).own)
        assertEquals(":app:core", model.subtreeOf(Path.of("/work/fx/app/core/build.gradle")).own)
    }

    @Test
    fun `a project whose path only starts like another is not below it`() {
        val parsed =
            TaskModel.parse(
                """{"version":1,"rootFolder":"/r","projects":[
                    {"path":":app","name":"app","folder":"/r/app","buildFile":"/r/app/build.gradle","tasks":[]},
                    {"path":":app2","name":"app2","folder":"/r/app2","buildFile":"/r/app2/build.gradle","tasks":[]}]}""",
            )
        assertEquals(listOf(":app"), paths(parsed.subtreeOf(Path.of("/r/app/build.gradle"))))
    }

    @Test
    fun `the build file is compared normalized`() {
        assertEquals(listOf(":lib"), paths(model.subtreeOf(Path.of("/work/fx/app/../lib/build.gradle.kts"))))
    }

    @Test
    fun `a file of no project gets all projects and no own project`() {
        val subtree = model.subtreeOf(Path.of("/work/fx/buildSrc/build.gradle"))
        assertEquals(paths(model.projects), paths(subtree))
        assertNull(subtree.own)
    }

    @Test
    fun `groups are sorted, named ones alphabetically and other last, tasks by name`() {
        val project =
            GradleProject(
                ":",
                "r",
                Path.of("/r"),
                null,
                listOf(
                    GradleTask("z", ":z", "build", null),
                    GradleTask("plain", ":plain", null, null),
                    GradleTask("a", ":a", "build", null),
                    GradleTask("x", ":x", "Verification", null),
                    GradleTask("blank", ":blank", " ", null),
                    GradleTask("b", ":b", "alpha", null),
                ),
            )
        val groups = groups(project)
        assertEquals(listOf("alpha", "build", "Verification", "other"), groups.keys.toList())
        assertEquals(listOf("a", "z"), groups.getValue("build").map { it.name })
        assertEquals(listOf("blank", "plain"), groups.getValue("other").map { it.name })
    }

    @Test
    fun `bad JSON is reported with a message`() {
        val e = assertFailsWith<IllegalArgumentException> { TaskModel.parse("{not json") }
        assertTrue("JSON" in e.message.orEmpty())
    }

    @Test
    fun `a wrong version or shape is reported`() {
        assertTrue(
            "version" in
                assertFailsWith<IllegalArgumentException> { TaskModel.parse("""{"version":2}""") }.message.orEmpty(),
        )
        assertFailsWith<IllegalArgumentException> { TaskModel.parse("[]") }
        assertFailsWith<IllegalArgumentException> { TaskModel.parse("""{"version":1,"rootFolder":"/r"}""") }
        assertFailsWith<IllegalArgumentException> { TaskModel.parse("""{"version":1,"projects":[]}""") }
        assertFailsWith<IllegalArgumentException> {
            TaskModel.parse("""{"version":1,"rootFolder":"/r","projects":[{"path":":"}]}""")
        }
    }

    @Test
    fun `a build file reached through a symbolic link is the project's build file`(
        @TempDir dir: Path,
    ) {
        val real = dir.resolve("real").createDirectories()
        real.resolve("build.gradle").writeText("")
        val link = Files.createSymbolicLink(dir.resolve("link"), real)
        val realFile = real.toRealPath().resolve("build.gradle")
        val parsed =
            TaskModel.parse(
                """{"version":1,"rootFolder":"/r","projects":[
                    {"path":":","name":"r","folder":"/r","buildFile":"/r/build.gradle","tasks":[]},
                    {"path":":x","name":"x","folder":"${real.toRealPath()}","buildFile":"$realFile","tasks":[]},
                    {"path":":x:y","name":"y","folder":"/r/x/y","buildFile":"/r/x/y/build.gradle","tasks":[]}]}""",
            )
        // An unknown file would give all three projects.
        assertEquals(listOf(":x", ":x:y"), paths(parsed.subtreeOf(link.resolve("build.gradle"))))
    }

    @Test
    fun `a build file outside its project's folder gives no own project but the projects below`() {
        val parsed =
            TaskModel.parse(
                """{"version":1,"rootFolder":"/r","projects":[
                    {"path":":","name":"r","folder":"/r","buildFile":"/r/build.gradle","tasks":[]},
                    {"path":":x","name":"x","folder":"/r/x","buildFile":"/r/build/x.gradle","tasks":[]},
                    {"path":":x:y","name":"y","folder":"/r/x/y","buildFile":"/r/x/y/build.gradle","tasks":[]}]}""",
            )
        val subtree = parsed.subtreeOf(Path.of("/r/build/x.gradle"))
        assertNull(subtree.own)
        assertEquals(listOf(":x", ":x:y"), paths(subtree))
    }
}
