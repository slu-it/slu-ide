package dev.sluide.gradle.detect

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GradleCommandTest {
    @Test
    fun `an executable wrapper runs directly, quoted if its path needs it`() {
        assertEquals(
            "/p/gradlew :build",
            GradleCommand.run(GradleInstallation.Wrapper(Path.of("/p"), true), "", listOf(":build")),
        )
        assertEquals(
            "'/p/my project/gradlew' :build",
            GradleCommand.run(GradleInstallation.Wrapper(Path.of("/p/my project"), true), "", listOf(":build")),
        )
    }

    @Test
    fun `a wrapper that is not executable runs with sh`() {
        assertEquals(
            "sh /p/gradlew :build",
            GradleCommand.run(GradleInstallation.Wrapper(Path.of("/p"), false), "", listOf(":build")),
        )
    }

    @Test
    fun `the global gradle is just gradle, arguments before the tasks`() {
        assertEquals(
            "gradle --info -x test :a:clean :a:build",
            GradleCommand.run(GradleInstallation.Global, "--info -x test", listOf(":a:clean", ":a:build")),
        )
    }

    @Test
    fun `no gradle cannot run`() {
        assertFailsWith<IllegalArgumentException> { GradleCommand.run(GradleInstallation.None, "", listOf("a")) }
    }

    @Test
    fun `an apostrophe in an argument reaches the shell as part of one word`() {
        assertEquals(
            "gradle '-Pa=it'\\''s' :t",
            GradleCommand.run(GradleInstallation.Global, "\"-Pa=it's\"", listOf(":t")),
        )
    }

    @Test
    fun `loading tasks passes init script and output, all projects configured, the root project task`() {
        assertEquals(
            "/p/gradlew --init-script /i/init.gradle -Dsluide.taskList=/o/out.json " +
                "-Dorg.gradle.configuration-cache=false --no-configure-on-demand :sluideTaskList",
            GradleCommand.loadTasks(
                GradleInstallation.Wrapper(Path.of("/p"), true),
                Path.of("/i/init.gradle"),
                Path.of("/o/out.json"),
            ),
        )
        assertEquals(
            "gradle --init-script '/i d/init.gradle' '-Dsluide.taskList=/o d/out.json' " +
                "-Dorg.gradle.configuration-cache=false --no-configure-on-demand :sluideTaskList",
            GradleCommand.loadTasks(GradleInstallation.Global, Path.of("/i d/init.gradle"), Path.of("/o d/out.json")),
        )
    }
}
