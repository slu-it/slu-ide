package dev.sluide.databases.settings

import dev.sluide.settings.SettingsPage
import javafx.beans.property.StringProperty
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

/** Databases: page size, fetch-all cap and statement timeout. Nothing changes until the dialog's OK or Apply. */
@Component
class DatabasesSettingsPage(
    settings: DatabasesSettings,
) : SettingsPage {
    internal val model = DatabasesSettingsPageModel(settings)

    override val path = listOf("Databases")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The number fields, for tests. */
    internal lateinit var pageSizeField: TextField
    internal lateinit var fetchAllCapField: TextField
    internal lateinit var timeoutField: TextField

    private fun createNode(): Node {
        pageSizeField = field(model.pageSize)
        fetchAllCapField = field(model.fetchAllCap)
        timeoutField = field(model.statementTimeoutSeconds)
        val grid = GridPane().apply { hgap = 8.0 }
        grid.addRow(0, Label("Page size"), pageSizeField, hint("Rows per result page"))
        grid.addRow(1, Label("Fetch-all cap"), fetchAllCapField, hint("Most rows Last and Export fetch"))
        grid.addRow(2, Label("Statement timeout (s)"), timeoutField, hint("0 = no timeout"))
        grid.vgap = 6.0
        return VBox(8.0, grid).apply { styleClass += "databases-settings-page" }
    }

    private fun field(draft: StringProperty) =
        TextField().apply {
            prefColumnCount = 10
            textProperty().bindBidirectional(draft)
        }

    private fun hint(text: String) = Label(text).apply { styleClass += "preview-caption" }
}
