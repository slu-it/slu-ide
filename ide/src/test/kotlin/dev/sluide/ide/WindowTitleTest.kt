package dev.sluide.ide

import dev.sluide.git.GitHead
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowTitleTest {
    private val folder = Path.of("/work/slu-ide")

    @Test
    fun `without a folder the title is the app name`() {
        assertEquals("Thoreau", windowTitle(null, null))
        assertEquals("Thoreau", windowTitle(null, GitHead("master", "5f070b9aaaa")))
    }

    @Test
    fun `without a head the title has the folder name only`() {
        assertEquals("slu-ide – Thoreau", windowTitle(folder, null))
    }

    @Test
    fun `a head with neither branch nor commit is not shown`() {
        assertEquals("slu-ide – Thoreau", windowTitle(folder, GitHead(null, null)))
    }

    @Test
    fun `a branch is shown in brackets`() {
        assertEquals("slu-ide [master] – Thoreau", windowTitle(folder, GitHead("master", "5f070b9aaaa")))
        assertEquals("slu-ide [main] – Thoreau", windowTitle(folder, GitHead("main", null)))
    }

    @Test
    fun `a detached head shows the short commit`() {
        assertEquals(
            "slu-ide [detached at 5f070b9] – Thoreau",
            windowTitle(folder, GitHead(null, "5f070b9aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
        )
    }

    @Test
    fun `a root folder is shown by its path`() {
        assertEquals("/ [master] – Thoreau", windowTitle(Path.of("/"), GitHead("master", "5f070b9aaaa")))
    }
}
