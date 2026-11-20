package dev.sluide.jshell

import dev.sluide.java.JavaHomes
import dev.sluide.java.Jdk
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JShellJdksTest {
    @TempDir
    lateinit var dir: Path

    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val project by lazy { dir.resolve("project").createDirectories() }
    private val file by lazy { project.resolve("try.jsh") }

    private fun jdk(
        name: String,
        version: String = "21.0.1",
        modules: String? = null,
    ): Path {
        val home = dir.resolve("jdks").resolve(name)
        home
            .resolve("bin")
            .createDirectories()
            .resolve("java")
            .writeText("")
        home.resolve("release").writeText("JAVA_VERSION=\"$version\"\n" + (modules?.let { "MODULES=\"$it\"\n" } ?: ""))
        return home
    }

    private fun jdks(
        projectSetting: Path? = null,
        shell: Path? = null,
        thoreau: Path = jdk("thoreau", "25"),
    ) = JShellJdks(store, JavaHomes({ _, _ -> shell?.toString() }, emptyMap(), thoreau), { projectSetting })

    @Test
    fun `a chosen JDK is remembered for the file`() {
        val chosen = jdk("chosen")
        jdks().remember(file, project, chosen)
        assertEquals(chosen, jdks().remembered(file, project))
        assertEquals(
            chosen.toString(),
            store
                .fileData(file, project)
                ?.get("jshell")
                ?.get("jdk")
                ?.asString(),
        )
    }

    @Test
    fun `going back to the project's JDK removes the field and keeps the file's other data`() {
        store.updateFileData(file, project) { it.putArray("runArguments").add("-v") }
        jdks().remember(file, project, jdk("chosen"))
        jdks().remember(file, project, null)
        assertNull(jdks().remembered(file, project))
        assertEquals("""{"runArguments":["-v"]}""", store.fileData(file, project).toString())
    }

    @Test
    fun `going back to the project's JDK leaves no data for the file`() {
        jdks().remember(file, project, jdk("chosen"))
        jdks().remember(file, project, null)
        assertNull(store.fileData(file, project))
    }

    @Test
    fun `a field that is not ours is no choice`() {
        store.updateFileData(file, project) { it.put("jshell", "/x") }
        assertNull(jdks().remembered(file, project))
        store.updateFileData(file, project) { it.putObject("jshell").put("jdk", 3) }
        assertNull(jdks().remembered(file, project))
        store.updateFileData(file, project) { it.putObject("jshell").put("jdk", "   ") }
        assertNull(jdks().remembered(file, project))
        store.updateFileData(file, project) { it.putObject("jshell").put("jdk", "a\u0000b") }
        assertNull(jdks().remembered(file, project))
    }

    @Test
    fun `the chosen JDK wins over the project's`() {
        val chosen = jdk("chosen", "17.0.2")
        val jdks = jdks(projectSetting = jdk("project", "25"))
        jdks.remember(file, project, chosen)
        assertEquals(JShellJdk(Jdk(chosen, "17.0.2", 17), "chosen for this file", null), jdks.resolve(file, project))
    }

    @Test
    fun `without a choice the project's JDK is used`() {
        val projectJdk = jdk("project", "25")
        assertEquals(
            JShellJdk(Jdk(projectJdk, "25", 25), "the project's JDK", null),
            jdks(projectSetting = projectJdk).resolve(file, project),
        )
    }

    @Test
    fun `a chosen JDK that is gone falls back to the project's chain with a problem`() {
        val gone = dir.resolve("jdks/gone")
        val projectJdk = jdk("project", "25")
        val jdks = jdks(projectSetting = projectJdk)
        jdks.remember(file, project, gone)
        assertEquals(
            JShellJdk(
                Jdk(projectJdk, "25", 25),
                "the project's JDK",
                "The JDK chosen for this file, $gone, is gone or has no JShell; using the project's JDK.",
            ),
            jdks.resolve(file, project),
        )
    }

    @Test
    fun `a chosen JDK without JShell falls back as well`() {
        val runtime = jdk("runtime", "25", modules = "java.base java.desktop")
        val jdks = jdks()
        jdks.remember(file, project, runtime)
        val resolved = jdks.resolve(file, project)
        assertEquals("Thoreau's own JDK", resolved.source)
        assertEquals(
            "The JDK chosen for this file, $runtime, is gone or has no JShell; using Thoreau's own JDK.",
            resolved.problem,
        )
    }

    @Test
    fun `a project JDK without JShell is skipped for the next source`() {
        val old = jdk("old", "1.8.0_392")
        val shellJdk = jdk("shell", "21.0.1")
        assertEquals(
            JShellJdk(
                Jdk(shellJdk, "21.0.1", 21),
                "the login shell's JAVA_HOME",
                "The project's JDK $old has no JShell (JDK 8); using the login shell's JAVA_HOME.",
            ),
            jdks(projectSetting = old, shell = shellJdk).resolve(file, project),
        )
    }

    @Test
    fun `no JDK with JShell anywhere gives none with a problem`() {
        val jdks = jdks(thoreau = jdk("thoreau", "25", modules = "java.base"))
        assertEquals(
            JShellJdk(
                null,
                "none",
                "No JDK with JShell (JDK 9 or newer) was found: choose one in the project settings or set JAVA_HOME.",
            ),
            jdks.resolve(file, project),
        )
        val gone = dir.resolve("jdks/gone")
        jdks.remember(file, project, gone)
        assertEquals(
            "The JDK chosen for this file, $gone, is gone or has no JShell; no other JDK with JShell was found.",
            jdks.resolve(file, project).problem,
        )
    }
}
