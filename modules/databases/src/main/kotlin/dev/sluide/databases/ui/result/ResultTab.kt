package dev.sluide.databases.ui.result

import dev.sluide.databases.result.TableRef
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.Tab
import org.kordamp.ikonli.javafx.FontIcon

/**
 * A closable tab of the result area: a console result or, with [ref], the data tab of a table. A pinned tab shows a
 * pin and is not replaced by the next result.
 */
internal class ResultTab(
    host: ResultHost,
    val ref: TableRef?,
    private val closeOthers: (ResultTab) -> Unit,
    /** Asks whether the unsubmitted edits of the tab with this title may be dropped. */
    confirmDiscard: (String) -> Boolean,
) : Tab(ref?.table ?: ResultTabsLogic.DEFAULT_TITLE) {
    val view = ResultView(host, ref) { columns -> if (ref == null) text = ResultTabsLogic.title(columns) }

    var pinned = false
        private set

    val info: TabInfo get() = TabInfo(pinned, ref != null, view.pendingEdits)

    init {
        content = view.node
        isClosable = true
        setOnCloseRequest { if (view.pendingEdits && !confirmDiscard(text)) it.consume() }
        setOnClosed { view.close() }
        val pin = MenuItem(ResultTabsLogic.pinLabel(false)).apply { setOnAction { setPinned(!pinned) } }
        val others = MenuItem("Close Other Result Tabs").apply { setOnAction { closeOthers(this@ResultTab) } }
        contextMenu = ContextMenu(pin, others)
        contextMenu.setOnShowing { pin.text = ResultTabsLogic.pinLabel(pinned) }
    }

    fun setPinned(value: Boolean) {
        pinned = value
        graphic = if (value) FontIcon("mdi2p-pin") else null
    }
}
