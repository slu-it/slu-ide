package dev.sluide.docker.run

import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.DirectoryChooser

/** The row editors of the Run dialog. */
internal class RunRows(
    private val volumeNames: List<String>,
) {
    fun port(
        initial: String?,
        changed: () -> Unit,
    ): EditorRow<String> {
        val field = field(initial.orEmpty(), "8080:80, 127.0.0.1:8080:80/tcp or 80", changed)
        return EditorRow(row(field)) { field.text }
    }

    fun variable(
        initial: RunEnvVar?,
        changed: () -> Unit,
    ): EditorRow<RunEnvVar> {
        val name = field(initial?.name.orEmpty(), "NAME", changed).apply { prefColumnCount = 10 }
        val value = field(initial?.value.orEmpty(), "value", changed)
        return EditorRow(row(name, value)) { RunEnvVar(name.text, value.text) }
    }

    fun volume(
        initial: RunVolume?,
        changed: () -> Unit,
    ): EditorRow<RunVolume> {
        val source =
            ComboBox<String>().apply {
                isEditable = true
                items.setAll(volumeNames)
                editor.text = initial?.source.orEmpty()
                editor.promptText = "/host/path, ./path, ~/path or volume"
                editor.textProperty().addListener { _, _, _ -> changed() }
                prefWidth = 220.0
                HBox.setHgrow(this, Priority.ALWAYS)
            }
        val choose =
            Button("Choose…").apply {
                setOnAction {
                    val folder = DirectoryChooser().showDialog(scene?.window)
                    if (folder != null) source.editor.text = folder.absolutePath
                }
            }
        val target = field(initial?.target.orEmpty(), "/path in the container", changed)
        val readOnly =
            CheckBox("read-only").apply {
                isSelected = initial?.readOnly ?: false
                selectedProperty().addListener { _, _, _ -> changed() }
            }
        return EditorRow(
            HBox(6.0, source, choose, target, readOnly).apply { alignment = javafx.geometry.Pos.CENTER_LEFT },
        ) {
            RunVolume(source.editor.text, target.text, readOnly.isSelected)
        }
    }

    private fun field(
        text: String,
        prompt: String,
        changed: () -> Unit,
    ) = TextField(text).apply {
        promptText = prompt
        textProperty().addListener { _, _, _ -> changed() }
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private fun row(vararg fields: TextField) = HBox(6.0, *fields).apply { alignment = javafx.geometry.Pos.CENTER_LEFT }
}
