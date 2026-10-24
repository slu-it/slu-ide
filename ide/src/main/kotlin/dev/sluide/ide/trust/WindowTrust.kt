package dev.sluide.ide.trust

import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustState
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Stage
import java.nio.file.Path

/**
 * The trust bar of one window and the questions behind it (ADR 0053): it says which tools are off until the folder is
 * trusted, with Review… to decide, and asks about the first findings after the folder was opened. [folder] is the
 * window's project folder. Used on the JavaFX thread.
 */
internal class WindowTrust(
    private val projectTrust: ProjectTrust,
    private val folder: () -> Path?,
) {
    /**
     * Asks whether to trust what the checks of the folder found (ADR 0053): `true` to trust it. Shows a dialog; tests
     * replace it, since it blocks.
     */
    internal var confirmTrust: (Stage, ProjectTrustState) -> Boolean = { stage, state ->
        askToTrust(stage, folder() ?: Path.of(""), state)
    }

    private val message = Label()
    private val review = Button("Review…")

    /** Says which tools are off until the folder is trusted, with Review… to decide; hidden otherwise. */
    internal val bar =
        HBox(message, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, review).apply {
            styleClass += "trust-bar"
            isVisible = false
            isManaged = false
        }

    /** Whether the folder's findings were asked about since it was opened; later ones show only the bar. */
    private var asked = false

    /** Lets Review… decide for [stage]'s window. */
    fun attach(stage: Stage) {
        review.setOnAction { reviewTrust(stage, projectTrust.state.get()) }
    }

    /**
     * Shows the trust bar while tools of the folder are off. The first findings after opening the folder are asked
     * about at once; later ones, found when something changed, only show the bar, so that no dialog interrupts work.
     */
    fun follow(
        stage: Stage,
        state: ProjectTrustState,
    ) {
        val text = trustBarMessage(state)
        bar.isVisible = text != null
        bar.isManaged = text != null
        if (state is ProjectTrustState.Closed) asked = false
        if (text == null) return
        message.text = text
        if (state is ProjectTrustState.Ask && !state.changed && !asked) {
            asked = true
            // Not while the state is being set: the dialog waits in a nested event loop.
            Platform.runLater { if (projectTrust.state.get() === state) reviewTrust(stage, state) }
        }
    }

    /**
     * Asks whether to trust what [state] found and decides. If the findings changed while the dialog was open, the
     * answer is not taken, and the new findings are asked about.
     */
    private fun reviewTrust(
        stage: Stage,
        state: ProjectTrustState,
    ) {
        if (state !is ProjectTrustState.Ask && state !is ProjectTrustState.Untrusted) return
        if (projectTrust.decide(state, confirmTrust(stage, state))) return
        // The new findings may be set already, or still on their way to follow, which then asks.
        asked = false
        val now = projectTrust.state.get()
        if (now is ProjectTrustState.Ask && now != state) {
            asked = true
            Platform.runLater { if (projectTrust.state.get() === now) reviewTrust(stage, now) }
        }
    }
}

/** What the trust bar says about [state]: which tools are off and why; `null` while no tool is off. */
internal fun trustBarMessage(state: ProjectTrustState): String? {
    if (state !is ProjectTrustState.Ask && state !is ProjectTrustState.Untrusted) return null
    val tools = toolsOff(state)
    val (verb, subject, target) =
        if (state.flagged.size ==
            1
        ) {
            Triple("is", "it", "it")
        } else {
            Triple("are", "they", "them")
        }
    return when {
        state.flagged.all { it.findings == null } -> {
            "$tools $verb off: this folder could not be checked."
        }

        state is ProjectTrustState.Ask && state.changed -> {
            "$tools $verb off: what this folder makes $target run changed."
        }

        else -> {
            "$tools $verb off: this folder defines commands $subject would run."
        }
    }
}
