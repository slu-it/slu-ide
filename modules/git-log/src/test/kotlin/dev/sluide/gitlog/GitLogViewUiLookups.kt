package dev.sluide.gitlog

import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.filter.FilterButton
import dev.sluide.gitlog.filter.FilterItem
import dev.sluide.gitlog.filter.FilterPopup
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TableRow
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Lookups of the shown table, the details and the filter popups of a [GitLogViewUiFixture].

internal fun GitLogViewUiFixture.shownSubjects(robot: FxRobot): List<String> =
    robot
        .lookup(".git-log-table .table-row-cell")
        .queryAllAs(TableRow::class.java)
        .filter { !it.isEmpty && it.item != null }
        .sortedBy { it.index }
        .map { (it.item as GitLogRow).commit.subject }

internal fun GitLogViewUiFixture.placeholder(robot: FxRobot) =
    robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

internal fun GitLogViewUiFixture.clipboard() =
    WaitForAsyncUtils
        .asyncFx<String> {
            Clipboard.getSystemClipboard().string
        }.get()

internal fun GitLogViewUiFixture.select(
    robot: FxRobot,
    vararg indices: Int,
) {
    robot.interact {
        view.table.selectionModel.clearSelection()
        indices.forEach(view.table.selectionModel::select)
    }
}

internal val GitLogViewUiFixture.details get() = view.detailsPane

internal fun GitLogViewUiFixture.showsDetailsOf(subject: String) =
    details.scroll.isVisible && !details.placeholder.isVisible && details.subject.text == subject

internal fun GitLogViewUiFixture.placeholderShown() =
    if (details.placeholder.isVisible &&
        !details.scroll.isVisible
    ) {
        details.placeholder.text
    } else {
        null
    }

internal fun GitLogViewUiFixture.status(robot: FxRobot) =
    robot.lookup(".git-log-status").queryAs(Label::class.java).text

internal val GitLogViewUiFixture.bar get() = view.filterBar

/** Fires a key press at [node], with the shortcut key (Ctrl, Cmd on macOS) if [shortcut]. */
internal fun GitLogViewUiFixture.press(
    robot: FxRobot,
    node: Node,
    code: KeyCode,
    shortcut: Boolean = false,
) {
    val mac = System.getProperty("os.name").startsWith("Mac")
    robot.interact {
        Event.fireEvent(
            node,
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
        )
    }
    WaitForAsyncUtils.waitForFxEvents()
}

/** The texts of the items a filter popup lists, in order. */
internal fun GitLogViewUiFixture.items(popup: FilterPopup) = popup.list.items.map { it.text }

/** The list cell of [popup] showing [text]. */
internal fun GitLogViewUiFixture.listCell(
    popup: FilterPopup,
    text: String,
): ListCell<*> =
    popup.list
        .lookupAll(".list-cell")
        .filterIsInstance<ListCell<*>>()
        .single { (it.item as? FilterItem)?.text == text }

/** Clicks the list cell of [popup] showing [text]. */
internal fun GitLogViewUiFixture.clickItem(
    robot: FxRobot,
    popup: FilterPopup,
    text: String,
    clickCount: Int = 1,
) {
    WaitForAsyncUtils.waitForFxEvents()
    val cell = listCell(popup, text)
    // A double click: a first click, then one with a click count of 2.
    for (count in 1..clickCount) click(robot, cell, count)
}

/**
 * Waits until the popup of [filter] lists [expected], showing it again if it was closed meanwhile: popups close
 * when another test JVM on the display takes the window's focus, and only a showing popup takes items read later.
 * If it stayed open, the items reached it while it was showing (`FilterPopup.refresh`).
 */
internal fun GitLogViewUiFixture.eventuallyLists(
    filter: FilterButton,
    expected: List<String>,
) {
    var shownAgain = false
    var showingWhenListed = false
    eventually {
        if (!filter.popup.popup.isShowing) {
            filter.button.fire()
            shownAgain = true
        }
        assertEquals(expected, items(filter.popup))
        showingWhenListed = filter.popup.popup.isShowing
    }
    if (!shownAgain) assertTrue(showingWhenListed, "the items reached the open popup")
}

/** The indices of the table's rows in view, the first and last possibly cut off. */
internal fun GitLogViewUiFixture.visibleRows(): IntRange {
    val flow = view.table.lookup(".virtual-flow") as VirtualFlow<*>
    return flow.firstVisibleCell.index..flow.lastVisibleCell.index
}
