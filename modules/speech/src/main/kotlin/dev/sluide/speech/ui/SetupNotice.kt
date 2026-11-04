package dev.sluide.speech.ui

import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.install.InstallState
import dev.sluide.speech.install.formatMegabytes
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Subscription

/**
 * The notice above the toolbar while the engine cannot speak: one row per problem, with Download (progress and Cancel
 * while it downloads) for a missing model and Check Again for the others. It is hidden without problems. When a
 * download ends it asks the model to check again; nothing starts then.
 */
internal class SetupNotice(
    private val model: ReadAloudModel,
) {
    val node: VBox =
        VBox(4.0).apply {
            styleClass += "speech-notice"
            padding = Insets(6.0, 10.0, 6.0, 10.0)
            isVisible = false
            isManaged = false
        }

    private var subscription: Subscription? = null

    fun show(problems: List<SetupProblem>) {
        subscription?.unsubscribe()
        subscription = null
        node.children.setAll(problems.map(::row))
        node.isVisible = problems.isNotEmpty()
        node.isManaged = problems.isNotEmpty()
    }

    fun dispose() {
        subscription?.unsubscribe()
        subscription = null
    }

    private fun row(problem: SetupProblem): HBox =
        when (problem) {
            is SetupProblem.ModelMissing -> modelRow(problem)
            is SetupProblem.ToolMissing -> textRow("${problem.tool} not found: ${problem.hint.replace("`", "")}")
            is SetupProblem.Broken -> textRow(problem.message)
        }

    private fun textRow(text: String) = HBox(10.0, label(text), checkAgain()).apply { alignment = Pos.CENTER_LEFT }

    private fun label(text: String) =
        Label(text).apply {
            isWrapText = true
            HBox.setHgrow(this, Priority.SOMETIMES)
        }

    private fun checkAgain() =
        Button("Check Again").apply {
            id = "speech-check-again"
            isFocusTraversable = false
            setOnAction { model.checkAgain() }
        }

    private fun modelRow(problem: SetupProblem.ModelMissing): HBox {
        val row = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }
        val message = "Kokoro voice model not installed (${formatMegabytes(problem.bytes)})."
        val state = model.installState()

        fun render(current: InstallState?) {
            row.children.setAll(
                when (current) {
                    is InstallState.Downloading -> downloading(current.progress)

                    is InstallState.Failed -> listOf(label(current.message), download(problem))

                    // Installed already (another window downloaded it): the check below clears the notice.
                    is InstallState.Installed -> listOf(label("Kokoro voice model installed."))

                    else -> listOf(label(message), download(problem))
                },
            )
        }
        render(state?.value)
        if (state?.value is InstallState.Installed) model.checkAgain()
        var last = state?.value
        subscription =
            state?.subscribe { now ->
                val before = last
                last = now
                render(now)
                if (before is InstallState.Downloading && now is InstallState.Installed) model.checkAgain()
            }
        return row
    }

    private fun download(problem: SetupProblem.ModelMissing) =
        Button("Download (${formatMegabytes(problem.bytes)})").apply {
            id = "speech-download"
            isFocusTraversable = false
            setOnAction { model.download() }
        }

    private fun downloading(progress: Double) =
        listOf(
            Label("Downloading Kokoro voice model"),
            ProgressBar(progress).apply { id = "speech-download-progress" },
            Label("${(progress * 100).toInt()} %"),
            Button("Cancel").apply {
                id = "speech-download-cancel"
                isFocusTraversable = false
                setOnAction { model.cancelDownload() }
            },
        )
}
