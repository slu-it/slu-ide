package dev.sluide.docker.dockerfile

import dev.sluide.docker.EngineState
import dev.sluide.docker.cli.FakeDockerCli
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The decisions of the Build pane without controls: tags, history, labels, enablement, marks and stored paths. */
class BuildRulesTest {
    private val available = EngineState.Available(FakeDockerCli().engine)

    @Test
    fun `tags are separated by spaces or commas`() {
        assertEquals(listOf("a", "b:1", "c"), tagsOf(" a,b:1 ,, c "))
        assertEquals(emptyList(), tagsOf("  "))
    }

    @Test
    fun `the history puts the text on top once, keeps ten and ignores blank text`() {
        val full = (1..10).map { "t$it" }

        assertEquals(listOf("t3") + (full - "t3"), withTagHistory(full, " t3 "))
        assertEquals(listOf("new") + full.take(9), withTagHistory(full, "new"))
        assertEquals(full, withTagHistory(full, "  "))
    }

    @Test
    fun `a build is named by its first tag, else the file`() {
        assertEquals("build myapp:1.0", buildLabel(BuildOptions(tags = "myapp:1.0, x"), "Dockerfile"))
        assertEquals("build app.Dockerfile", buildLabel(BuildOptions(), "app.Dockerfile"))
    }

    @Test
    fun `Build needs the engine and a command, Run also a built tag and no running build`() {
        val command = BuildPreview.Command("docker build .")

        assertTrue(buildEnabled(available, command))
        assertFalse(buildEnabled(available, BuildPreview.Problem("x")))
        assertFalse(buildEnabled(EngineState.Checking, command))
        assertTrue(runImageEnabled(available, "app", running = false))
        assertFalse(runImageEnabled(available, "app", running = true))
        assertFalse(runImageEnabled(available, null, running = false))
        assertFalse(runImageEnabled(EngineState.Unavailable("down"), "app", running = false))
    }

    @Test
    fun `the Run tooltip says why it is disabled`() {
        assertEquals("Build with a tag first", runImageTooltip(null, running = false))
        assertEquals("Runs the image once the build has succeeded", runImageTooltip(null, running = true))
        assertEquals("Run app:1 in a new container", runImageTooltip("app:1", running = false))
    }

    @Test
    fun `the note says that a build waits for the engine, else why the engine is not available`() {
        assertEquals("Build waits for the engine…", buildNote(EngineState.Checking, waiting = true))
        assertEquals("Connecting to Docker…", buildNote(EngineState.Checking, waiting = false))
        assertEquals(null, buildNote(available, waiting = false))
    }

    @Test
    fun `a target not among the stages is missing, ignoring case`() {
        assertFalse(isMissingTarget("", listOf("a")))
        assertFalse(isMissingTarget("Build", listOf("build")))
        assertTrue(isMissingTarget("old", listOf("build")))
    }

    @Test
    fun `a chosen context is stored relative to the Dockerfile's folder when inside it`() {
        val folder = Path.of("/work/app")

        assertEquals("", contextEntry(folder, folder))
        assertEquals("sub/dir", contextEntry(folder, folder.resolve("sub/dir")))
        assertEquals("/work", contextEntry(folder, Path.of("/work")))
        assertEquals("/work/app2", contextEntry(folder, Path.of("/work/app2")))
    }

    @Test
    fun `an empty build arg value removes it, so that the default applies`() {
        assertEquals(mapOf("A" to "1", "B" to "2"), withArg(mapOf("A" to "1"), "B", "2"))
        assertEquals(mapOf("B" to "2"), withArg(mapOf("A" to "1", "B" to "2"), "A", ""))
    }
}
