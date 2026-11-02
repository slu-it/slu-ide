package dev.sluide.databases.ui.result

import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.ConnectionEntry
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import java.time.LocalTime

private fun systemClipboard(text: String) {
    Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
}

/**
 * The result area under a console (ADR 0121): the Output tab, then the result tabs and data tabs. Everything runs on
 * the FX thread; [fx] hands answers of the session's worker threads back to it. [clock] and [clipboard] are seams of
 * tests.
 */
class ResultArea(
    private val entry: ConnectionEntry,
    private val pageSize: () -> Int,
    private val fetchAllCap: () -> Int,
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val clock: () -> LocalTime = LocalTime::now,
    private val clipboard: (String) -> Unit = ::systemClipboard,
) {
    private val output = OutputLog(clipboard)
    private val outputTab =
        Tab(ResultTabsLogic.OUTPUT_TITLE, output.list).apply {
            isClosable = false
            styleClass += "output-tab"
        }
    private val tabs =
        TabPane(outputTab).apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
            styleClass += "result-area"
        }

    private val host =
        object : ResultHost {
            override val entry: ConnectionEntry = this@ResultArea.entry

            override fun pageSize(): Int = this@ResultArea.pageSize.invoke()

            override fun fetchAllCap(): Int = this@ResultArea.fetchAllCap.invoke()

            override fun fx(action: Runnable) = this@ResultArea.fx.invoke(action)

            override fun logOutcome(outcome: StatementOutcome) = output.add(OutputText.line(clock(), outcome))

            override fun log(
                text: String,
                error: Boolean,
            ) = this@ResultArea.log(text, error)

            override fun copyToClipboard(text: String) = clipboard(text)
        }

    val node: Node =
        tabs.also {
            it.stylesheets += checkNotNull(ResultArea::class.java.getResource("result.css")).toExternalForm()
        }

    /** Whether any tab has unsubmitted edits (the editing package fills it in). */
    val pendingEdits: Boolean get() = false

    private fun resultTabs(): List<ResultTab> = tabs.tabs.filterIsInstance<ResultTab>()

    /**
     * Shows a statement's outcome (FX thread): Rows go to a result tab (the first unpinned one's content when
     * [replaceUnpinned], else a new tab), every outcome is logged to Output; Updated, Failed and Cancelled select
     * Output.
     */
    fun show(
        outcome: StatementOutcome,
        replaceUnpinned: Boolean = true,
    ) {
        host.logOutcome(outcome)
        if (outcome !is StatementOutcome.Rows) {
            tabs.selectionModel.select(outputTab)
            return
        }
        val existing = resultTabs()
        val index = if (replaceUnpinned) ResultTabsLogic.replaceIndex(existing.map { it.info }) else null
        val tab = index?.let { existing[it] } ?: newTab(null)
        tab.view.load(outcome)
        tabs.selectionModel.select(tab)
    }

    /** A line in Output, red when [error]. */
    fun log(
        text: String,
        error: Boolean = false,
    ) = output.add(OutputText.note(clock(), text, error))

    /** Opens the table's data tab, or selects the one open for it, and loads it. */
    fun openData(ref: TableRef) {
        if (entry.session.get() == null) {
            log("Cannot open ${ref.table}: not connected", true)
            tabs.selectionModel.select(outputTab)
            return
        }
        val tab = resultTabs().firstOrNull { it.ref == ref } ?: newTab(ref)
        tabs.selectionModel.select(tab)
        tab.view.reload()
    }

    private fun newTab(ref: TableRef?): ResultTab {
        val tab = ResultTab(host, ref, ::closeOthers)
        tabs.tabs += tab
        return tab
    }

    private fun closeOthers(keep: ResultTab) {
        for (other in resultTabs().filter { it !== keep }) {
            other.view.close()
            tabs.tabs.remove(other)
        }
    }

    /** Closes every tab's cursor (console closed or disconnected). */
    fun close() {
        resultTabs().forEach { it.view.close() }
    }
}
