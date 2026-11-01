package dev.sluide.docker.tab.details

import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.tab.RowAction
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Pos
import javafx.geometry.VPos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import java.net.URI

/** The buttons of a details header, one per [RowAction]; they never shrink, they wrap when the bar is narrow. */
internal class ActionBar {
    val node = FlowPane().apply { styleClass += "docker-actions" }

    /** Shows the buttons of [actions]; without any the bar takes no space (compose projects, empty selection). */
    fun show(actions: List<RowAction>) {
        node.isVisible = actions.isNotEmpty()
        node.isManaged = actions.isNotEmpty()
        node.children.setAll(
            actions.map { action ->
                Button(action.title).apply {
                    minWidth = Region.USE_PREF_SIZE
                    isDisable = !action.enabled
                    setOnAction { action.run() }
                }
            },
        )
    }
}

/** A details header: the title and a dimmed subtitle, and below them the [actions], which wrap when narrow. */
internal class DetailsHeader {
    val title = Label().apply { styleClass += "docker-details-title" }
    val subtitle = Label().apply { styleClass += "docker-details-state" }
    val actions = ActionBar()
    private val titleLine =
        HBox(title, subtitle).apply {
            styleClass += "docker-details-titleline"
            minWidth = TITLE_MIN
        }
    val node = VBox(titleLine, actions.node).apply { styleClass += "docker-details-header" }

    private companion object {
        const val TITLE_MIN = 120.0
    }
}

/** An Overview: label and values per row; links open through [openUrl], collapsible rows fold. */
internal class OverviewView(
    private val openUrl: (URI) -> Unit,
) {
    private val grid =
        GridPane().apply {
            styleClass += "docker-overview"
            columnConstraints.setAll(
                ColumnConstraints().apply {
                    minWidth = Region.USE_PREF_SIZE
                    hgrow = Priority.NEVER
                },
                ColumnConstraints().apply { hgrow = Priority.ALWAYS },
            )
        }
    private val note = Label().apply { styleClass += "docker-placeholder" }
    private val holder = StackPane(grid)
    val node: Node = ScrollPane(holder).apply { isFitToWidth = true }

    /** The rows shown, `null` while a note is. */
    private var shownRows: List<OverviewRow>? = null

    /** Shows [rows]; the same rows again change nothing, so a folded section stays as the user left it. */
    fun show(rows: List<OverviewRow>) {
        if (rows == shownRows) return
        shownRows = rows
        grid.children.clear()
        rows.forEachIndexed { index, row ->
            val label =
                Label(row.label).apply {
                    styleClass += "docker-overview-label"
                    minWidth = Region.USE_PREF_SIZE
                }
            GridPane.setValignment(label, VPos.TOP)
            val values = VBox(2.0, *row.values.map(::valueNode).toTypedArray())
            val content: Node =
                if (row.collapsible) {
                    TitledPane("${row.values.size} ${row.label.lowercase()}", values).apply {
                        isExpanded = false
                        isAnimated = false
                    }
                } else {
                    values
                }
            grid.addRow(index, label, content)
        }
        holder.children.setAll(grid)
    }

    /** Shows [text] (Loading…, a failure) instead of the rows. */
    fun showNote(text: String) {
        shownRows = null
        note.text = text
        holder.children.setAll(note)
    }

    private fun valueNode(value: OverviewValue): Node =
        when (value) {
            is OverviewValue.Text -> {
                Label(value.text).apply {
                    isWrapText = true
                    value.tooltip?.let { tooltip = Tooltip(it) }
                }
            }

            is OverviewValue.Link -> {
                Hyperlink(value.text).apply {
                    tooltip = Tooltip(value.uri.toString())
                    setOnAction { openUrl(value.uri) }
                }
            }
        }
}

/** The raw `inspect` JSON, read-only and highlighted. */
internal class InspectView {
    private val display = CodeEditors.display()
    private val note = Label().apply { styleClass += "docker-placeholder" }
    val node = StackPane(display.node, note).apply { StackPane.setAlignment(note, Pos.TOP_LEFT) }

    /** The JSON shown, for tests. */
    val text: String get() = display.text

    fun clear() = showNote("")

    fun loading() = showNote("Loading…")

    fun show(json: String) {
        note.isVisible = false
        display.node.isVisible = true
        display.show(json, Highlighters.json)
    }

    fun failed(message: String) = showNote(message)

    private fun showNote(text: String) {
        display.show("", null)
        display.node.isVisible = false
        note.text = text
        note.isVisible = true
    }
}

/** The Environment tab: Name | Value, values masked unless Show values is on; Copy on a row's context menu. */
internal class EnvironmentView {
    val showValues = CheckBox("Show values")

    private val table =
        TableView<EnvVariable>().apply {
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            placeholder = Label("No variables")
            columns +=
                TableColumn<EnvVariable, String>("Name").apply {
                    setCellValueFactory { SimpleStringProperty(it.value.name) }
                    prefWidth = 220.0
                }
            columns +=
                TableColumn<EnvVariable, String>("Value").apply {
                    setCellValueFactory { SimpleStringProperty(envValueText(it.value.value, showValues.isSelected)) }
                }
            setRowFactory {
                TableRow<EnvVariable>().apply {
                    val copy = MenuItem("Copy")
                    copy.setOnAction {
                        item?.let {
                            Clipboard.getSystemClipboard().setContent(
                                ClipboardContent().apply { putString(envCopyText(it)) },
                            )
                        }
                    }
                    val menu = ContextMenu(copy)
                    contextMenuProperty().bind(emptyProperty().map { empty -> if (empty) null else menu })
                }
            }
            VBox.setVgrow(this, Priority.ALWAYS)
        }

    val node = VBox(HBox(showValues).apply { styleClass += "docker-env-bar" }, table)

    init {
        showValues.selectedProperty().addListener { _ -> table.refresh() }
    }

    fun show(env: List<EnvVariable>) {
        if (table.items != env) table.items.setAll(env)
    }
}
