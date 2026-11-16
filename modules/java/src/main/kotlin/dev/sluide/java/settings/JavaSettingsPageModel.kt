package dev.sluide.java.settings

import dev.sluide.java.JavaSettings
import dev.sluide.java.JavaWorkspaceUse
import dev.sluide.java.JavaWorkspaces
import dev.sluide.java.MEMORY_CHOICES_MB
import dev.sluide.java.WorkspaceInfo
import javafx.beans.property.IntegerProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleIntegerProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToLong

private const val UNKNOWN = "unknown"
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** One line of the Java workspaces table: the folder and what the table shows of it. */
internal data class WorkspaceRow(
    val folder: Path,
    val project: String,
    val size: String,
    val lastUsed: String,
)

/** The label and state of the table's Delete button for the selected row. */
internal data class DeleteButton(
    val text: String,
    val disabled: Boolean,
)

/**
 * The Java page's parts beyond the shared server-tool page (ADR 0179): the maximum memory, a draft applied by [apply],
 * and the table of jdtls's workspaces with its Delete. Listing and deleting run on [io]; their results reach the
 * properties through [dispatch] (the FX thread). [confirmDelete] asks the question and answers whether to delete.
 */
internal class JavaSettingsPageModel(
    private val settings: JavaSettings,
    private val workspaces: JavaWorkspaces,
    private val use: JavaWorkspaceUse,
    private val confirmDelete: (question: String) -> Boolean,
    private val io: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The draft maximum memory in MB. */
    val memory: IntegerProperty = SimpleIntegerProperty(settings.maxMemoryMb.get())

    /** The workspaces, newest use first. */
    val rows: ObservableList<WorkspaceRow> = FXCollections.observableArrayList()

    private val loadingWrapper = ReadOnlyBooleanWrapper()

    /** Whether the workspaces are being listed. */
    val loading: ReadOnlyBooleanProperty = loadingWrapper.readOnlyProperty

    private val messageWrapper = ReadOnlyStringWrapper("")

    /** Why the last delete failed; empty for none. */
    val message: ReadOnlyStringProperty = messageWrapper.readOnlyProperty

    private var generation = 0

    /** Shows the stored memory (dropping an unapplied choice) and lists the workspaces. */
    fun load() {
        memory.set(settings.maxMemoryMb.get())
        messageWrapper.set("")
        refresh()
    }

    /** Stores the chosen memory; the running server restarts with it (the installer's command listeners). */
    fun apply() = settings.applyMaxMemory(memory.get())

    /** The sizes the combo box offers, in MB: the six of the page and the stored one if it is another. */
    fun memoryChoices(): List<Int> = (MEMORY_CHOICES_MB + settings.maxMemoryMb.get()).distinct().sorted()

    /** The Delete button for the selected [row]: off without one and while a window uses its workspace. */
    fun deleteButton(row: WorkspaceRow?): DeleteButton =
        when {
            row == null -> DeleteButton("Delete…", true)
            inUse(row) -> DeleteButton("Delete… (in use)", true)
            else -> DeleteButton("Delete…", false)
        }

    /**
     * Asks, then deletes the workspace of [row] on [io] and lists again; nothing for one in use (also if a window
     * starts using it during the question).
     */
    fun delete(row: WorkspaceRow) {
        if (inUse(row)) return
        if (!confirmDelete(deleteQuestion(row))) return
        io.execute {
            val failure =
                try {
                    // Checked again here: a window may have started using the folder since the question.
                    if (use.isInUse(row.folder)) {
                        "a window is using it"
                    } else {
                        workspaces.delete(row.folder)
                        null
                    }
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                } catch (e: IllegalArgumentException) {
                    e.message ?: e.javaClass.simpleName
                }
            dispatch(
                Runnable {
                    messageWrapper.set(failure?.let { "Cannot delete ${row.project}: $it" } ?: "")
                    refresh()
                },
            )
        }
    }

    private fun inUse(row: WorkspaceRow) = row.folder.toAbsolutePath().normalize() in use.inUse

    private fun refresh() {
        val mine = ++generation
        loadingWrapper.set(true)
        io.execute {
            val listed = workspaces.list().sortedWith(NEWEST_FIRST).map(::row)
            dispatch(
                Runnable {
                    if (mine != generation) return@Runnable
                    rows.setAll(listed)
                    loadingWrapper.set(false)
                },
            )
        }
    }

    private fun row(info: WorkspaceInfo) =
        WorkspaceRow(
            info.folder,
            info.projectPath?.toString() ?: UNKNOWN,
            humanSize(info.bytes),
            info.lastUsed?.atZone(zone)?.format(TIME) ?: UNKNOWN,
        )

    companion object {
        private val NEWEST_FIRST =
            compareByDescending<WorkspaceInfo, Instant?>(nullsFirst()) { it.lastUsed }
                .thenBy { it.projectPath?.toString() ?: it.folder.toString() }

        /** `1 GB`..`8 GB` for the sizes the page offers, `<n> MB` for any other stored one. */
        fun memoryLabel(megabytes: Int): String =
            if (megabytes in MEMORY_CHOICES_MB) "${megabytes / 1024} GB" else "$megabytes MB"

        /** [bytes] in decimal units with one decimal (`1.5 kB`, `2.0 MB`), whole bytes below 1000. */
        fun humanSize(bytes: Long): String {
            if (bytes < 1000) return "$bytes B"
            var value = bytes / 1000.0
            var unit = 0
            while (unit < 2 && (value * 10).roundToLong() >= 10_000) {
                value /= 1000
                unit++
            }
            return "%.1f %s".format(Locale.ROOT, value, listOf("kB", "MB", "GB")[unit])
        }

        /** What is lost: the folder with its indexes and import cache, permanently; it is rebuilt on the next open. */
        fun deleteQuestion(row: WorkspaceRow): String {
            val named = if (row.project == UNKNOWN) "an unknown project" else row.project
            return "Delete the Java workspace of $named? Its folder ${row.folder} (${row.size}) with jdtls's indexes " +
                "and import cache is removed permanently and rebuilt the next time the project is opened."
        }
    }
}
