package dev.sluide.java

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaProjectSettingsTest {
    @TempDir
    lateinit var dir: Path

    private val project: Path by lazy { Files.createDirectories(dir.resolve("work/app")) }
    private val store by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val settings by lazy { JavaProjectSettings(store) { it.run() } }

    @Test
    fun `a project without a JDK has none`() {
        assertNull(settings.jdk(project))
    }

    @Test
    fun `the JDK set for a project is read back for that project only`() {
        settings.setJdk(project, Path.of("/opt/jdk-21"))

        assertEquals(Path.of("/opt/jdk-21"), settings.jdk(project))
        assertEquals(Path.of("/opt/jdk-21"), settings.jdk(project.resolve("../app")))
        assertNull(settings.jdk(dir.resolve("work/other")))
        assertEquals(
            "/opt/jdk-21",
            store
                .read(project)
                ?.get("java")
                ?.get("jdk")
                ?.asString(),
        )
    }

    @Test
    fun `setting null removes the JDK and the empty java field`() {
        settings.setJdk(project, Path.of("/opt/jdk-21"))

        settings.setJdk(project, null)

        assertNull(settings.jdk(project))
        assertNull(store.read(project)?.get("java"))
    }

    @Test
    fun `other fields of java and of the project data stay as they are`() {
        store.update(project) { root ->
            root.putObject("java").put("other", 7)
            root.put("elsewhere", "kept")
        }

        settings.setJdk(project, Path.of("/opt/jdk-21"))
        settings.setJdk(project, null)

        val data = store.read(project)
        assertEquals(7, data?.get("java")?.get("other")?.asInt())
        assertEquals("kept", data?.get("elsewhere")?.asString())
    }

    @Test
    fun `a JDK of the wrong type counts as none`() {
        store.update(project) { it.putObject("java").put("jdk", 5) }

        assertNull(settings.jdk(project))
    }

    @Test
    fun `a blank JDK counts as none`() {
        store.update(project) { it.putObject("java").put("jdk", " ") }

        assertNull(settings.jdk(project))
    }

    @Test
    fun `a java field that is JSON null is replaced`() {
        store.update(project) { it.putNull("java") }

        settings.setJdk(project, Path.of("/opt/jdk-21"))

        assertEquals(Path.of("/opt/jdk-21"), settings.jdk(project))
    }

    @Test
    fun `a java field that is not an object is refused and left alone`() {
        store.update(project) { it.put("java", "text") }

        assertFailsWith<IOException> { settings.setJdk(project, Path.of("/opt/jdk-21")) }

        assertEquals("text", store.read(project)?.get("java")?.asString())
    }

    @Test
    fun `setJdk throws an IOException when the store cannot save`() {
        dir.resolve("projects").writeText("a file where the store wants a folder")

        assertFailsWith<IOException> { settings.setJdk(project, Path.of("/opt/jdk-21")) }
    }

    @Test
    fun `listeners hear of a change with the normalized folder and stop when closed`() {
        val heard = mutableListOf<Path>()
        val subscription = settings.onChange { heard.add(it) }

        settings.setJdk(project.resolve("../app"), Path.of("/opt/jdk-21"))
        subscription.close()
        settings.setJdk(project, null)

        assertEquals(listOf(project.toAbsolutePath().normalize()), heard)
    }

    @Test
    fun `listeners hear nothing of a failed change`() {
        dir.resolve("projects").writeText("a file")
        val heard = mutableListOf<Path>()
        settings.onChange { heard.add(it) }

        assertFailsWith<IOException> { settings.setJdk(project, Path.of("/opt/jdk-21")) }

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `the change is dispatched, not called inline`() {
        val queued = mutableListOf<Runnable>()
        val deferred = JavaProjectSettings(store) { queued += it }
        val heard = mutableListOf<Path>()
        deferred.onChange { heard.add(it) }

        deferred.setJdk(project, Path.of("/opt/jdk-21"))

        assertTrue(heard.isEmpty())
        queued.single().run()
        assertEquals(1, heard.size)
    }

    @Test
    fun `the offer is not declined until the project declines it`() {
        assertTrue(!settings.outputIgnoreDeclined(project))

        settings.declineOutputIgnore(project)

        assertTrue(settings.outputIgnoreDeclined(project))
        assertTrue(!settings.outputIgnoreDeclined(dir.resolve("work/other")))
        assertEquals(
            true,
            store
                .read(project)
                ?.get("java")
                ?.get("outputIgnore")
                ?.get("declined")
                ?.asBoolean(),
        )
    }

    @Test
    fun `a decision that is no boolean counts as not declined`() {
        store.update(project) { it.putObject("java").putObject("outputIgnore").put("declined", "true") }

        assertTrue(!settings.outputIgnoreDeclined(project))
    }

    @Test
    fun `a decision that is JSON null is replaced when declining`() {
        store.update(project) { it.putObject("java").putNull("outputIgnore") }

        settings.declineOutputIgnore(project)

        assertTrue(settings.outputIgnoreDeclined(project))
    }

    @Test
    fun `declining keeps the JDK and a JDK change keeps the decision`() {
        settings.setJdk(project, Path.of("/opt/jdk-21"))
        settings.declineOutputIgnore(project)
        assertEquals(Path.of("/opt/jdk-21"), settings.jdk(project))

        settings.setJdk(project, null)

        assertTrue(settings.outputIgnoreDeclined(project))
    }

    @Test
    fun `declining is not announced to the JDK listeners`() {
        val heard = mutableListOf<Path>()
        settings.onChange { heard.add(it) }

        settings.declineOutputIgnore(project)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a decision of the wrong type counts as not declined and declining is refused`() {
        store.update(project) { it.putObject("java").put("outputIgnore", "text") }
        assertTrue(!settings.outputIgnoreDeclined(project))

        assertFailsWith<IOException> { settings.declineOutputIgnore(project) }

        assertEquals(
            "text",
            store
                .read(project)
                ?.get("java")
                ?.get("outputIgnore")
                ?.asString(),
        )
    }
}
