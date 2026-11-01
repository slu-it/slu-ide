package dev.sluide.docker.settings

import dev.sluide.settings.SettingsPage
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.springframework.stereotype.Component

/** Docker: the executable of the CLI. Nothing changes in the IDE until the dialog's OK or Apply. */
@Component
class DockerSettingsPage(
    settings: DockerSettings,
) : SettingsPage {
    internal val model = DockerSettingsPageModel(settings)

    override val path = listOf("Docker")

    override val node: Node by lazy(::createNode)

    override fun load() = model.load()

    override fun apply() = model.apply()

    /** The executable field, for tests. */
    internal lateinit var executable: TextField

    private fun createNode(): Node {
        executable =
            TextField().apply {
                promptText = "docker"
                prefColumnCount = 32
                textProperty().bindBidirectional(model.executable)
            }
        val grid = GridPane().apply { hgap = 8.0 }
        grid.addRow(0, Label("Executable"), executable)
        GridPane.setHgrow(executable, Priority.ALWAYS)
        val note =
            Label(
                "docker, podman, or the full path of either; found through your login shell like in a terminal.",
            ).apply {
                styleClass += "preview-caption"
                isWrapText = true
            }
        return VBox(8.0, grid, note).apply { styleClass += "docker-settings-page" }
    }
}
