package dev.sluide.databases.ui.result

import dev.sluide.databases.result.PageState
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon

private const val RELOAD_TIP = "Reload"

/** Why Reload is off in a console tab whose statement is not a plain query. */
internal const val RELOAD_OFF_TIP = "Reload is off for statements that change data"

/** What the buttons of the [PagingBar] do. */
internal class PagingActions(
    val first: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val last: () -> Unit,
    val reload: () -> Unit,
    /** Enter in the page size field, with its text. */
    val pageSize: (String) -> Unit,
)

/**
 * The toolbar above the grid: paging buttons, the page label, the page size field, Reload, a spacer and the status.
 * More buttons are added with [addAction], which places them before the spacer.
 */
internal class PagingBar(
    actions: PagingActions,
) {
    private val first = iconButton("mdi2p-page-first", "First Page", actions.first)
    private val previous = iconButton("mdi2c-chevron-left", "Previous Page", actions.previous)
    private val next = iconButton("mdi2c-chevron-right", "Next Page", actions.next)
    private val last = iconButton("mdi2p-page-last", "Last Page", actions.last)
    private val reload = iconButton("mdi2r-refresh", "Reload", actions.reload)
    private val label = Label().apply { styleClass += "result-page-label" }
    val pageField =
        TextField().apply {
            promptText = "500"
            prefColumnCount = 4
            isFocusTraversable = false
            tooltip = Tooltip("Rows per page")
            setOnAction { actions.pageSize(text) }
        }
    private val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
    private val status = Label().apply { styleClass += "result-status" }

    val node =
        HBox(4.0, first, previous, next, last, label, pageField, reload, spacer, status).apply {
            styleClass += "result-toolbar"
            alignment = Pos.CENTER_LEFT
            padding = Insets(2.0, 6.0, 2.0, 6.0)
        }

    private fun iconButton(
        icon: String,
        tip: String,
        action: () -> Unit,
    ) = Button().apply {
        graphic = FontIcon(icon)
        styleClass += "result-button"
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { action() }
    }

    /** Puts [item] after Reload, before the spacer. */
    fun addAction(item: Node) {
        node.children.add(node.children.indexOf(spacer), item)
    }

    /** [state] null: nothing loaded yet. While [busy] the buttons are disabled. */
    fun update(
        state: PageState?,
        busy: Boolean,
        status: String,
        error: Boolean,
        reloadAllowed: Boolean = true,
        /** Paging, page size and Reload are off with this tooltip (pending edits, a transposed grid); null: on. */
        lockTip: String? = null,
    ) {
        val locked = lockTip != null
        val off = busy || locked
        pageField.isDisable = off
        pageField.tooltip.text = lockTip ?: "Rows per page"
        for ((button, tip) in listOf(
            first to "First Page",
            previous to "Previous Page",
            next to "Next Page",
            last to "Last Page",
        )) {
            button.tooltip.text = lockTip ?: tip
        }
        first.isDisable = off || state?.canFirst != true
        previous.isDisable = off || state?.canPrevious != true
        next.isDisable = off || state?.canNext != true
        last.isDisable = off || state?.canLast != true
        reload.isDisable = off || !reloadAllowed
        reload.tooltip.text =
            when {
                lockTip != null -> lockTip
                reloadAllowed -> RELOAD_TIP
                else -> RELOAD_OFF_TIP
            }
        label.text = state?.label ?: ""
        this.status.text = status
        this.status.styleClass.remove("result-error")
        if (error) this.status.styleClass += "result-error"
    }
}
