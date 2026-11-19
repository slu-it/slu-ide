package dev.sluide.editorarea

import javafx.beans.property.SimpleStringProperty
import javafx.scene.Group
import kotlin.test.Test
import kotlin.test.assertFalse

class NoDropTargetsTest {
    private val tab =
        object : EditorTab {
            override val title = SimpleStringProperty("plain")
            override val tooltip = "plain"
            override val content = Group()
        }

    @Test
    fun `without targets a dragged header is over none and drops nowhere`() {
        assertFalse(NoDropTargets.hover(tab, Side.LEFT, 10.0, 10.0))
        assertFalse(NoDropTargets.drop(tab, Side.RIGHT, 10.0, 10.0))
    }
}
