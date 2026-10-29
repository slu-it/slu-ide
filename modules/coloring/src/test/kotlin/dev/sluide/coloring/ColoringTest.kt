package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.ColoringState
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
    fun `show inspects the color, leaves the slots alone and shows the tab`() {
        var shown = 0
        coloring.showTab = {
            shown++
            // A new tab edits the slot of its tool tab, which must not replace the shown color in the inspector.
            coloring.state.editIn(ColoringState.MIX)
        }
        val red = CssColor.fromRgb8(255, 0, 0)

        coloring.show(red)

        assertEquals(red, coloring.state.inspected.get())
        assertEquals(CssColor.fromRgb8(0x3b, 0x82, 0xf6), coloring.state.editingColor.get())
        assertEquals(1, shown)
    }
}
