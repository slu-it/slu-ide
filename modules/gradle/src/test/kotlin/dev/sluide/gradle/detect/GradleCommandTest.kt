package dev.sluide.gradle.detect

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "-Pname=a b|'-Pname=a b'",
            "it's|'it'\\''s'",
            "a;b|'a;b'",
            "\$HOME|'\$HOME'",
            "-Dx=y:z/1.2,3@%+_-|-Dx=y:z/1.2,3@%+_-",
            "=x|'=x'",
            "%x|'%x'",
            "@x|'@x'",
        ],
    )
    fun `words are quoted only when needed`(
        word: String,
        quoted: String,
    ) {
        assertEquals(quoted, GradleCommand.quote(word))
    }

    @Test
    fun `an empty word is quoted`() {
        assertEquals("''", GradleCommand.quote(""))
    }

    @Test
    fun `arguments are split at white space and quotes group`() {
        assertEquals(
            listOf("--info", "-Pa=b c", "d e", "x", "", "q\"r"),
            GradleCommand.tokenize("  --info  -Pa='b c'  \"d e\" x'' '' \"q\\\"r\"  "),
        )
    }

    @Test
    fun `a backslash outside quotes escapes the next character like in sh`() {
        assertEquals(listOf("-Pa=b c", "x'y", "\\"), GradleCommand.tokenize("-Pa=b\\ c x\\'y \\\\"))
    }

    @Test
    fun `an apostrophe in an argument reaches the shell as part of one word`() {
        assertEquals(
            "gradle '-Pa=it'\\''s' :t",
            GradleCommand.run(GradleInstallation.Global, "\"-Pa=it's\"", listOf(":t")),
        )
    }

    @Test
    fun `an unbalanced quote is an error with a message`() {
        val e = assertFailsWith<IllegalArgumentException> { GradleCommand.tokenize("--info \"-Pa=b") }
        assertTrue("quote" in e.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { GradleCommand.tokenize("it's") }
    }

    @Test
    fun `loading tasks passes the init script and the output and switches off the configuration cache`() {
        assertEquals(
            "/p/gradlew --init-script /i/init.gradle -Dsluide.taskList=/o/out.json " +
                "-Dorg.gradle.configuration-cache=false sluideTaskList",
            GradleCommand.loadTasks(
                GradleInstallation.Wrapper(Path.of("/p"), true),
                Path.of("/i/init.gradle"),
                Path.of("/o/out.json"),
            ),
        )
        assertEquals(
            "gradle --init-script '/i d/init.gradle' '-Dsluide.taskList=/o d/out.json' " +
                "-Dorg.gradle.configuration-cache=false sluideTaskList",
            GradleCommand.loadTasks(GradleInstallation.Global, Path.of("/i d/init.gradle"), Path.of("/o d/out.json")),
        )
    }
}
