package dev.sluide.docker.tab

import dev.sluide.docker.model.ContainerState
import javafx.application.Platform
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window

/** The [DockerDialogs] as JavaFX dialogs over the window of the tab ([owner]). */
internal class FxDockerDialogs(
    private val owner: () -> Window?,
) : DockerDialogs {
    override fun confirm(
        title: String,
        message: String,
        confirm: String,
    ): Boolean = ask(title, Label(message).apply { isWrapText = true }, confirm) { true } == true

    override fun confirmList(
        title: String,
        message: String,
        items: List<String>,
        confirm: String,
    ): Boolean {
        val list =
            ListView<String>().apply {
                this.items.setAll(items)
                prefHeight = (items.size.coerceIn(2, 10) * 24 + 4).toDouble()
                prefWidth = 420.0
            }
        return ask(title, VBox(8.0, Label(message).apply { isWrapText = true }, list), confirm) { true } == true
    }

    override fun removeContainer(
        name: String,
        state: ContainerState,
    ): Boolean? {
        val volumes = CheckBox("Also remove anonymous volumes")
        val message = Label(removeContainerMessage(name, state)).apply { isWrapText = true }
        return ask("Remove Container", VBox(10.0, message, volumes), "Remove") { volumes.isSelected }
    }

    override fun removeImage(
        reference: String,
        stoppedUsers: List<String>,
    ): Boolean? {
        val message = Label("Remove the image $reference?").apply { isWrapText = true }
        if (stoppedUsers.isEmpty()) return ask("Remove Image", message, "Remove") { false }
        val force = CheckBox("Force: stopped containers use it (${stoppedUsers.joinToString(", ")})")
        return ask("Remove Image", VBox(10.0, message, force), "Remove") { force.isSelected }
    }

    override fun askPull(): String? {
        val field = TextField().apply { promptText = "name:tag, e.g. nginx:1.27" }
        return ask("Pull Image", VBox(6.0, Label("Image:"), field), "Pull", field) { field.text.trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    override fun askVolumeName(): String? {
        val field = TextField()
        return ask("Create Volume", VBox(6.0, Label("Name:"), field), "Create", field) { field.text.trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    override fun askNetwork(): NewNetwork? {
        val field = TextField()
        val driver =
            ChoiceBox<String>().apply {
                items.setAll(NETWORK_DRIVERS)
                value = NETWORK_DRIVERS.first()
            }
        val content = VBox(6.0, Label("Name:"), field, Label("Driver:"), driver)
        return ask("Create Network", content, "Create", field) { NewNetwork(field.text.trim(), driver.value) }
            ?.takeIf { it.name.isNotEmpty() }
    }

    /** Shows a dialog with [content] and the [confirm] button; its answer is [result], `null` if cancelled. */
    private fun <T> ask(
        title: String,
        content: Region,
        confirm: String,
        focus: TextField? = null,
        result: () -> T,
    ): T? {
        val confirmButton = ButtonType(confirm, ButtonBar.ButtonData.OK_DONE)
        val dialog =
            Dialog<ButtonType>().apply {
                owner()?.let(::initOwner)
                this.title = title
                headerText = null
                dialogPane.content = content.apply { minWidth = 360.0 }
                dialogPane.buttonTypes.setAll(confirmButton, ButtonType.CANCEL)
                focus?.let { field ->
                    dialogPane
                        .lookupButton(
                            confirmButton,
                        ).disableProperty()
                        .bind(field.textProperty().map { it.isBlank() })
                    setOnShown { Platform.runLater(field::requestFocus) }
                }
            }
        return if (dialog.showAndWait().orElse(null) == confirmButton) result() else null
    }
}
