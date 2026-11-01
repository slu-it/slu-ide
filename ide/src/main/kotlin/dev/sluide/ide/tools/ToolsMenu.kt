package dev.sluide.ide.tools

import dev.sluide.editorarea.EditorTab
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCombination

/** A tool of the Tools or Services menu (ADR 0110, ADR 0118): one tab per window, optionally on a key. */
internal class WindowTool(
    val title: String,
    val createTab: () -> EditorTab,
    val accelerator: KeyCombination? = null,
)

/**
 * A menu of window tools, such as Tools and Services: one item per [tools] entry. Choosing a tool opens its tab, or
 * selects the tab it opened before while that is still among the [tabs]. Used on the JavaFX thread.
 */
internal class ToolsMenu(
    private val tabs: () -> List<EditorTab>,
    private val openTab: (EditorTab) -> Unit,
    private val select: (EditorTab) -> Unit,
    val tools: List<WindowTool>,
    /** The menu's title. */
    val title: String = "Tools",
) {
    private val remembered = mutableMapOf<WindowTool, EditorTab>()

    /** The menu named [title]; an item has its tool's accelerator, if any. */
    fun menu(): Menu =
        Menu(
            title,
            null,
            *tools
                .map { tool ->
                    MenuItem(tool.title).apply {
                        accelerator = tool.accelerator
                        setOnAction { open(tool) }
                    }
                }.toTypedArray(),
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
