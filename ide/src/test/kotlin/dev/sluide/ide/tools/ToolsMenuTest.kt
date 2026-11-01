package dev.sluide.ide.tools

import dev.sluide.editorarea.EditorTab
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ToolsMenuTest {
    private class FakeTab(
        name: String,
    ) : EditorTab {
        override val title: ObservableValue<String> = SimpleStringProperty(name)
        override val tooltip = name
        override val content: Node get() = error("not shown")
    }

    private val open = mutableListOf<EditorTab>()
    private val selected = mutableListOf<EditorTab>()
    private val created = mutableListOf<String>()

    private fun tool(
        name: String,
        accelerator: KeyCombination? = null,
    ) = WindowTool(name, { FakeTab(name).also { created += name } }, accelerator)

    private fun menu(vararg tools: WindowTool) =
        ToolsMenu({
            open.toList()
        }, { open += it }, { selected += it }, tools.toList())

    @Test
    fun `the menu has its title and an item per tool with the tool's accelerator, which opens the tool`() {
        val alt8 = KeyCodeCombination(KeyCode.DIGIT8, KeyCombination.ALT_DOWN)
        val tools = listOf(tool("Docker", alt8), tool("Other"))

        val services = ToolsMenu({ open.toList() }, { open += it }, { selected += it }, tools, "Services").menu()

        assertEquals("Services", services.text)
        assertEquals(listOf("Docker", "Other"), services.items.map { it.text })
        assertEquals(listOf(alt8, null), services.items.map { it.accelerator })
        services.items
            .first()
            .onAction
            .handle(null)
        assertEquals(listOf("Docker"), created)
        assertEquals("Tools", menu(tool("Coloring")).menu().text, "the default title")
    }

    @Test
    fun `the first choice creates and opens the tab`() {
        val menu = menu(tool("Coloring"))

        menu.open(menu.tools.single())

        assertEquals(listOf("Coloring"), created)
        assertEquals(1, open.size)
        assertEquals(emptyList(), selected)
    }

    @Test
    fun `a second choice selects the same tab instead of creating another`() {
        val menu = menu(tool("Coloring"))
        menu.open(menu.tools.single())

        menu.open(menu.tools.single())

        assertEquals(listOf("Coloring"), created)
        assertEquals(1, open.size)
        assertSame(open.single(), selected.single())
    }

    @Test
    fun `a closed tab is created again`() {
        val menu = menu(tool("Coloring"))
        menu.open(menu.tools.single())
        val first = open.single()
        open.clear()

        menu.open(menu.tools.single())

        assertEquals(listOf("Coloring", "Coloring"), created)
        assertNotSame(first, open.single())
        assertEquals(emptyList(), selected)
    }

    @Test
    fun `two tools keep separate tabs`() {
        val menu = menu(tool("One"), tool("Two"))
        val (one, two) = menu.tools

        menu.open(one)
        menu.open(two)
        menu.open(one)

        assertEquals(listOf("One", "Two"), created)
        assertEquals(1, selected.size)
        assertSame(open[0], selected.single())
    }
}
