package dev.sluide.databases.ui.result

import dev.sluide.databases.result.ExportFormat
import dev.sluide.databases.result.ResultExport
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.RowCursor
import dev.sluide.databases.ui.ConnectionEntry
import javafx.scene.control.Menu
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import org.kordamp.ikonli.javafx.FontIcon

/** What the export menu needs of the area the result sits in. */
internal interface ExportHost {
    val entry: ConnectionEntry

    fun fetchAllCap(): Int

    /** Runs [action] on the FX thread. */
    fun fx(action: Runnable)

    fun copyToClipboard(text: String)

    /** Opens [text] as a new scratch file with [extension] (no dot). */
    fun openScratch(
        extension: String,
        text: String,
    )
}

/** What the export menu needs of the view it belongs to. */
internal interface ExportSource {
    val cursor: RowCursor?

    /** The one table the result comes from (qualified, for SQL INSERTs), else null. */
    val tableName: String?

    /** Whether the grid shows the transposed rows (its selection is no result of the table). */
    val transposed: Boolean

    /** The selected cells' rectangle as shown, null without a selection. */
    fun selection(): Pair<List<ColumnInfo>, List<List<Any?>>>?

    /** A line in the status of the toolbar. */
    fun say(
        text: String,
        error: Boolean,
    )
}

/**
 * The export and copy actions of a result: the toolbar's [button], and [copyMenus] and [scratchMenu] for the grid's
 * context menu. Copy and Open as Scratch File work on the selection or on all rows (read again from the cursor, at
 * most the fetch cap; the text is built on the thread of the session, off the FX thread).
 */
internal class ExportMenu(
    private val host: ExportHost,
    private val source: ExportSource,
) {
    val button =
        MenuButton().apply {
            graphic = FontIcon("mdi2e-export-variant")
            styleClass += "result-button"
            tooltip = Tooltip("Export or Copy")
            isFocusTraversable = false
            items.setAll(copyMenus() + scratchMenu("Open All as Scratch File"))
        }

    /** "Copy Selection as" and "Copy All as", new ones on every call (a menu item has one parent). */
    fun copyMenus(): List<Menu> =
        listOf(
            menu("Copy Selection as", selection = true) { _, text -> host.copyToClipboard(text) },
            menu("Copy All as", selection = false) { _, text -> host.copyToClipboard(text) },
        )

    /** All rows as a scratch file, one entry per format. */
    fun scratchMenu(title: String): Menu =
        menu(title, selection = false) { format, text -> host.openScratch(format.extension, text) }

    private fun menu(
        title: String,
        selection: Boolean,
        deliver: (ExportFormat, String) -> Unit,
    ): Menu {
        val formats =
            ExportFormat.entries.map { format ->
                format to
                    MenuItem(format.label).apply {
                        setOnAction { if (selection) selected(format, deliver) else all(format, deliver) }
                    }
            }
        return Menu(title).apply {
            items.setAll(formats.map { it.second })
            // The transposed selection is no row of the table: INSERTs of it would be wrong.
            if (selection) {
                setOnShowing {
                    formats.first { it.first == ExportFormat.SQL_INSERT }.second.isDisable = source.transposed
                }
            }
        }
    }

    private fun write(
        format: ExportFormat,
        columns: List<ColumnInfo>,
        rows: List<List<Any?>>,
    ): String =
        ResultExport.write(
            format,
            host.entry.config
                .get()
                .kind,
            columns,
            rows,
            source.tableName ?: "result",
        )

    private fun selected(
        format: ExportFormat,
        deliver: (ExportFormat, String) -> Unit,
    ) {
        val (columns, rows) = source.selection() ?: return
        if (format == ExportFormat.SQL_INSERT && source.transposed) return
        deliver(format, write(format, columns, rows))
    }

    private fun all(
        format: ExportFormat,
        deliver: (ExportFormat, String) -> Unit,
    ) {
        val cursor = source.cursor
        if (cursor == null) {
            source.say("Nothing to export", true)
            return
        }
        source.say("Exporting…", false)
        cursor
            .fetchAll(host.fetchAllCap())
            .thenApply { rows -> rows.size to write(format, cursor.columns, rows) }
            .whenComplete { result, error ->
                host.fx {
                    if (error != null) {
                        val cause = error.cause ?: error
                        source.say("Export failed: ${cause.message ?: cause.javaClass.simpleName}", true)
                        return@fx
                    }
                    val (count, text) = result
                    deliver(format, text)
                    source.say(
                        if (cursor.capped) "Exported the first $count rows (limit reached)" else "Exported $count rows",
                        false,
                    )
                }
            }
    }
}
