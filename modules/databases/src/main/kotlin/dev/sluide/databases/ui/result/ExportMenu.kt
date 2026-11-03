package dev.sluide.databases.ui.result

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.databases.connect.DatabaseKind
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
import java.util.concurrent.Executor

/** Builds the export texts of all rows; its thread ends when idle. */
internal val EXPORT_THREAD: Executor = NamedThreads.single("sluide-db-export")

/** What the export menu needs of the area the result sits in. */
internal interface ExportHost {
    val entry: ConnectionEntry

    fun fetchAllCap(): Int

    /** Runs [action] on the FX thread. */
    fun fx(action: Runnable)

    /** Where the text of all rows is built, off the FX thread. */
    fun exportExecutor(): Executor

    fun copyToClipboard(text: String)

    /** Opens [text] as a new scratch file named [baseName] with [extension] (no dot). */
    fun openScratch(
        baseName: String,
        extension: String,
        text: String,
    )
}

/** What the export menu needs of the view it belongs to. */
internal interface ExportSource {
    val cursor: RowCursor?

    /** The one table the result comes from (qualified, for SQL INSERTs), else null. */
    val tableName: String?

    /** The plain name of that table (no schema, unquoted; the scratch file's name), else null. */
    val tableBaseName: String?

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
 * most the fetch cap; the text is built on the host's export executor, off the FX thread).
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
            menu("Copy Selection as", selection = true) { _, text, _ -> host.copyToClipboard(text) },
            menu("Copy All as", selection = false) { _, text, _ -> host.copyToClipboard(text) },
        )

    /** All rows as a scratch file, one entry per format. */
    fun scratchMenu(title: String): Menu =
        menu(title, selection = false) { format, text, baseName -> host.openScratch(baseName, format.extension, text) }

    private fun menu(
        title: String,
        selection: Boolean,
        deliver: (ExportFormat, String, String) -> Unit,
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

    /** The database kind and the INSERT target, read on the FX thread. */
    private fun target(): Pair<DatabaseKind, String> =
        host.entry.config
            .get()
            .kind to (source.tableName ?: "result")

    private fun selected(
        format: ExportFormat,
        deliver: (ExportFormat, String, String) -> Unit,
    ) {
        val (columns, rows) = source.selection() ?: return
        if (format == ExportFormat.SQL_INSERT && source.transposed) return
        val (kind, table) = target()
        val baseName = source.tableBaseName ?: "result"
        deliver(format, ResultExport.write(format, kind, columns, rows, table), baseName)
    }

    private fun all(
        format: ExportFormat,
        deliver: (ExportFormat, String, String) -> Unit,
    ) {
        val cursor = source.cursor
        if (cursor == null) {
            source.say("Nothing to export", true)
            return
        }
        source.say("Exporting…", false)
        val (kind, table) = target()
        // Read now: a run may replace the result meanwhile, and the rows belong to this one.
        val baseName = source.tableBaseName ?: "result"
        val columns = cursor.columns
        cursor
            .fetchAll(host.fetchAllCap())
            // Async also when the rows are cached and the future is complete: the text is never built on FX.
            .thenApplyAsync(
                { rows -> rows.size to ResultExport.write(format, kind, columns, rows, table) },
                host.exportExecutor(),
            ).whenComplete { result, error ->
                host.fx {
                    if (error != null) {
                        val cause = error.cause ?: error
                        source.say("Export failed: ${cause.message ?: cause.javaClass.simpleName}", true)
                        return@fx
                    }
                    val (count, text) = result
                    deliver(format, text, baseName)
                    source.say(
                        if (cursor.capped) "Exported the first $count rows (limit reached)" else "Exported $count rows",
                        false,
                    )
                }
            }
    }
}
