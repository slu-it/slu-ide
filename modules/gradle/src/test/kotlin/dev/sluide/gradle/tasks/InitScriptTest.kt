package dev.sluide.gradle.tasks

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitScriptTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the init script is extracted and replaced`() {
        val file = extractInitScript(dir.resolve("sub"))
        assertTrue("sluideTaskList" in file.readText())
        file.writeText("old")
        assertEquals(file, extractInitScript(dir.resolve("sub")))
        assertTrue("sluideTaskList" in file.readText())
    }

    // Each project lists its own tasks: a task of a subproject that resolves a configuration when it is created
    // failed the list of the root project (Gradle 9: "attempted without an exclusive lock"). Only with parallel
    // execution, which builds turn on in gradle.properties: without --parallel the old script passed this test too.
    @Test
    fun `Gradle lists the tasks of every project, also one that resolves a configuration on creation`() {
        val build = dir.resolve("build").createDirectories()
        build.resolve("settings.gradle").writeText("rootProject.name = 'fixture'\ninclude 'app'\n")
        build.resolve("build.gradle").writeText("tasks.register('hello') { group = 'demo' }\n")
        build.resolve("app").createDirectories().resolve("build.gradle").writeText(
            """
            configurations { bundle }
            dependencies { bundle files('bundle.txt') }
            tasks.register('eager') { description = "bundles ${'$'}{configurations.bundle.files.size()}" }
            """.trimIndent(),
        )
        val output = dir.resolve("tasks.json")

        GradleRunner
            .create()
            .withProjectDir(build.toFile())
            .withArguments(
                "--init-script",
                extractInitScript(dir.resolve("script")).toString(),
                "-Dsluide.taskList=$output",
                "-Dorg.gradle.configuration-cache=false",
                "--no-configure-on-demand",
                "--parallel",
                ":sluideTaskList",
            ).build()

        val model = TaskModel.parse(output.readText())
        assertEquals(listOf(":", ":app"), model.projects.map { it.path })
        assertEquals(
            "demo",
            model.projects[0]
                .tasks
                .single { it.name == "hello" }
                .group,
        )
        val eager = model.projects[1].tasks.single { it.name == "eager" }
        assertEquals(GradleTask("eager", ":app:eager", null, "bundles 1"), eager)
        assertTrue(model.projects.flatMap { it.tasks }.none { it.name.startsWith("sluideTaskList") })
    }
}
