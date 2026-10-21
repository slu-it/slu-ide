package dev.sluide.ide

import dev.sluide.git.GitHead
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WindowTitleTest {
    private val folder = Path.of("/work/slu-ide")
    private val version = "1.2.3"

    @Test
    fun `without a folder the title is the app name and version`() {
        assertEquals("Thoreau v1.2.3", windowTitle(null, null, version))
        assertEquals("Thoreau v1.2.3", windowTitle(null, GitHead("master", "5f070b9aaaa"), version))
    }

    @Test
    fun `without a head the title has the folder name only`() {
        assertEquals("slu-ide – Thoreau v1.2.3", windowTitle(folder, null, version))
    }

    @Test
    fun `a head with neither branch nor commit is not shown`() {
        assertEquals("slu-ide – Thoreau v1.2.3", windowTitle(folder, GitHead(null, null), version))
    }

    @Test
    fun `a branch is shown in brackets`() {
        assertEquals(
            "slu-ide [master] – Thoreau v1.2.3",
            windowTitle(folder, GitHead("master", "5f070b9aaaa"), version),
        )
        assertEquals("slu-ide [main] – Thoreau v1.2.3", windowTitle(folder, GitHead("main", null), version))
    }

    @Test
    fun `a detached head shows the short commit`() {
        assertEquals(
            "slu-ide [detached at 5f070b9] – Thoreau v1.2.3",
            windowTitle(folder, GitHead(null, "5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), version),
        )
    }

    @Test
    fun `a root folder is shown by its path`() {
        assertEquals(
            "/ [master] – Thoreau v1.2.3",
            windowTitle(Path.of("/"), GitHead("master", "5f070b9aaaa"), version),
        )
    }

    @Test
    fun `without a version the title ends with the app name`() {
        assertEquals("Thoreau", windowTitle(null, null, null))
        assertEquals("slu-ide [master] – Thoreau", windowTitle(folder, GitHead("master", "5f070b9aaaa"), null))
    }

    @Test
    fun `the version comes from the build`() {
        assertNotNull(APP_VERSION)
        assertEquals("Thoreau v$APP_VERSION", appTitle())
    }
}
