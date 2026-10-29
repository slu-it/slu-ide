package dev.sluide.ide.tools

import dev.sluide.editorarea.EditorTab
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem

/** A tool of the Tools menu (ADR 0110): one tab per window. */
internal class WindowTool(
    val title: String,
    val createTab: () -> EditorTab,
)

/**
 * The Tools menu: one item per [tools] entry. Choosing a tool opens its tab, or selects the tab it opened before while
 * that is still among the [tabs]. Used on the JavaFX thread.
 */
internal class ToolsMenu(
    private val tabs: () -> List<EditorTab>,
    private val openTab: (EditorTab) -> Unit,
    private val select: (EditorTab) -> Unit,
    val tools: List<WindowTool>,
) {
    private val remembered = mutableMapOf<WindowTool, EditorTab>()

    /** The "Tools" menu; its items have no accelerators. */
    fun menu(): Menu =
        Menu(
            "Tools",
            null,
            *tools.map { tool -> MenuItem(tool.title).apply { setOnAction { open(tool) } } }.toTypedArray(),
        )

    /** Selects the tab of [tool] while it is open, else creates, remembers and opens a new one. */
    fun open(tool: WindowTool) {
        val existing = remembered[tool]?.takeIf { it in tabs() }
        if (existing != null) {
            select(existing)
        } else {
            remembered.remove(tool)
            val tab = tool.createTab()
            remembered[tool] = tab
            openTab(tab)
        }
    }
}
