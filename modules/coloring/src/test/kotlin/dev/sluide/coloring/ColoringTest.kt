package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ColoringTest {
    @TempDir
    lateinit var dir: Path

    private val coloring by lazy {
        Coloring(SavedColors(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())))
    }

    @Test
    fun `show makes the color current and shows the tab`() {
        var shown = 0
        coloring.showTab = { shown++ }
        val red = CssColor.fromRgb8(255, 0, 0)

        coloring.show(red)

        assertEquals(red, coloring.state.current.get())
        assertEquals(1, shown)
    }
}
