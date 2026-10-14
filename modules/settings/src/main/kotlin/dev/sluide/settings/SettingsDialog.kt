package dev.sluide.settings

import javafx.event.ActionEvent
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.stage.Window
import java.io.IOException

private val log = System.getLogger(SettingsDialog::class.java.name)

/**
 * The settings dialog: a tree of the [pages] on the left, the selected page on the right. OK and Apply apply all
 * pages, Cancel drops their edits; the pages are loaded again whenever a dialog is created. Pages whose edits cannot
 * be stored are reported in an error message, and OK then keeps the dialog open.
 */
class SettingsDialog(
    private val pages: List<SettingsPage>,
    owner: Window? = null,
) : Dialog<ButtonType>() {
    /** A tree node: a group like "Display" (no page) or a page. */
    internal class Entry(
        val name: String,
        val page: SettingsPage?,
    ) {
        override fun toString() = name
    }

    val apply = ButtonType("Apply", ButtonBar.ButtonData.APPLY)
    internal val tree =
        TreeView(TreeItem(Entry("Settings", null))).apply {
            isShowRoot = false
            minWidth = 160.0
        }
    private val pageTitle = Label().apply { styleClass += "settings-page-title" }
    private val content = StackPane()

    /** The page whose content is shown. */
    var shownPage: SettingsPage? = null
        private set

    init {
        owner?.let(::initOwner)
        title = "Settings"
        isResizable = true
        pages.forEach { it.load() }
        pages.forEach(::addToTree)
        tree.selectionModel.selectedItemProperty().subscribe { item -> show(item) }
        tree.selectionModel.select(firstPageItem(tree.root))

        val pageArea =
            BorderPane(content).apply {
                top = pageTitle
                styleClass += "settings-page"
            }
        dialogPane.content =
            SplitPane(tree, pageArea).apply {
                setDividerPositions(0.25)
                SplitPane.setResizableWithParent(tree, false)
                setPrefSize(1000.0, 640.0)
            }
        dialogPane.stylesheets +=
            checkNotNull(SettingsDialog::class.java.getResource("settings.css")).toExternalForm()
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL, apply)
        dialogPane.lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION) { event ->
            if (!applyAll()) event.consume() // keeps the dialog open, the edits that failed are not lost
        }
        dialogPane.lookupButton(apply).addEventFilter(ActionEvent.ACTION) { event ->
            applyAll()
            event.consume() // keeps the dialog open
        }
    }

    /** Selects the page at [path] in the tree, e.g. `listOf("Display", "Fonts")`. */
    fun select(path: List<String>) {
        var item = tree.root
        for (name in path) item = item.children.firstOrNull { it.value.name == name } ?: return
        tree.selectionModel.select(item)
    }

    /** Applies every page, also after one failed, and reports the failures. Returns whether all pages were applied. */
    private fun applyAll(): Boolean {
        val failures =
            pages.mapNotNull { page ->
                try {
                    page.apply()
                    null
                } catch (e: IOException) {
                    log.log(System.Logger.Level.WARNING, "Cannot apply the settings page ${page.path}", e)
                    "${page.path.joinToString(" › ")}: ${e.message ?: e}"
                }
            }
        if (failures.isEmpty()) return true
        val window = dialogPane.scene?.window
        Alert(Alert.AlertType.ERROR, failures.joinToString("\n")).apply {
            initOwner(window)
            title = "Settings"
            headerText = "These settings could not be saved and are not applied."
            show()
        }
        return false
    }

    private fun addToTree(page: SettingsPage) {
        var parent = tree.root
        page.path.forEachIndexed { index, name ->
            val isPage = index == page.path.lastIndex
            parent =
                parent.children.firstOrNull { !isPage && it.value.page == null && it.value.name == name }
                    ?: TreeItem(Entry(name, if (isPage) page else null)).also {
                        it.isExpanded = true
                        parent.children += it
                    }
        }
    }

    /** Shows the page of [item], or for a group the first page below it. */
    private fun show(item: TreeItem<Entry>?) {
        val page = item?.let { firstPageItem(it) }?.value?.page
        shownPage = page
        pageTitle.text = page?.path?.joinToString(" › ") ?: ""
        content.children.setAll(listOfNotNull(page?.node))
    }

    private fun firstPageItem(item: TreeItem<Entry>): TreeItem<Entry>? =
        if (item.value.page != null) item else item.children.firstNotNullOfOrNull { firstPageItem(it) }
}
