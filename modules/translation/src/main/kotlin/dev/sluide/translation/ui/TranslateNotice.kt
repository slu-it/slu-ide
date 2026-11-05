package dev.sluide.translation.ui

import dev.sluide.translation.session.SetupProblem
import dev.sluide.translation.session.TranslateModel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/**
 * The notice above the toolbar while models are missing: what is missing with Download, while downloading a progress
 * bar, the percent and Cancel, after a failure its message with Download again. Hidden without a problem. The
 * controls stay the same nodes between updates, so a click is not lost to a progress tick.
 */
internal class TranslateNotice(
    private val model: TranslateModel,
) {
    private val message =
        Label().apply {
            isWrapText = true
            HBox.setHgrow(this, Priority.SOMETIMES)
        }
    private val download =
        Button().apply {
            id = "translate-download"
            isFocusTraversable = false
            setOnAction { model.download() }
        }
    private val progress = ProgressBar(0.0).apply { id = "translate-download-progress" }
    private val percent = Label()
    private val cancel =
        Button("Cancel").apply {
            id = "translate-download-cancel"
            isFocusTraversable = false
            setOnAction { model.cancelDownload() }
        }

    val node: HBox =
        HBox(10.0, message, download, progress, percent, cancel).apply {
            styleClass += "translate-notice"
            alignment = Pos.CENTER_LEFT
            padding = Insets(6.0, 10.0, 6.0, 10.0)
        }

    init {
        show(null)
    }

    fun show(problem: SetupProblem?) {
        node.isVisible = problem != null
        node.isManaged = problem != null
        if (problem == null) return
        val progressValue = problem.progress
        val downloading = progressValue != null
        message.text =
            when {
                downloading -> "Downloading translation models"
                problem.failure != null -> problem.failure
                else -> noticeText(problem, model::languageName)
            }
        download.text = downloadLabel(problem)
        progress.progress = progressValue ?: 0.0
        percent.text = "${((progressValue ?: 0.0) * 100).toInt()} %"
        for (node in listOf(progress, percent, cancel)) {
            node.isVisible = downloading
            node.isManaged = downloading
        }
        download.isVisible = !downloading
        download.isManaged = !downloading
    }
}
