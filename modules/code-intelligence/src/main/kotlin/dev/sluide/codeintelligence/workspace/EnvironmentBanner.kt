package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.languageservers.EnvironmentProblem
import javafx.collections.ObservableSet
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import java.nio.file.Path

/**
 * The environment banner of a bound editor (ADR 0151), looking like the server banners: the problem's message with
 * the action's button (Run uv sync), Not Now and Don't Ask Again, above the text while there is a problem. FX thread.
 */
internal class EnvironmentBanner(
    private val editor: ExtensibleCodeEditor,
    private val model: EnvironmentBannerModel,
) : AutoCloseable {
    private var handle: AutoCloseable? = null
    private var shown: EnvironmentProblem? = null
    private val subscription = model.problem.subscribe { problem -> render(problem) }

    private fun render(problem: EnvironmentProblem?) {
        if (problem == shown) return
        shown = problem
        handle?.close()
        handle = null
        if (problem == null) return
        val banner =
            HBox().apply {
                styleClass += listOf("editor-banner", "code-intelligence-banner")
                alignment = Pos.CENTER_LEFT
                id = "code-intelligence-environment"
            }
        banner.children +=
            listOfNotNull(
                Label(problem.message).apply {
                    isWrapText = true
                    minWidth = 0.0
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                problem.action?.let { button(it.label, "environment-run", model::run) },
                button("Not Now", "environment-not-now", model::notNow),
                button("Don't Ask Again", "environment-dont-ask", model::dontAskAgain),
            )
        handle = editor.addBanner(banner)
    }

    private fun button(
        label: String,
        id: String,
        action: () -> Unit,
    ) = Button(label).apply {
        this.id = "code-intelligence-$id"
        isFocusTraversable = false
        minWidth = Region.USE_PREF_SIZE
        setOnAction { action() }
    }

    override fun close() {
        subscription.unsubscribe()
        handle?.close()
        handle = null
        model.close()
    }
}

/**
 * The environment banner as a feature of every binding, with the window's Not Now ([notNow], by problem folder), the
 * user's Don't Ask Again ([prompts], id `<spec id>.environment`) and [runInTerminal] for the action's button.
 */
internal class EnvironmentBannerFeature(
    private val notNow: ObservableSet<Path>,
    private val prompts: InstallPrompts,
    private val runInTerminal: (folder: Path, name: String, command: String) -> Unit,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val session = binding.session
        val file = binding.file
        val spec = binding.spec
        val model =
            EnvironmentBannerModel(
                session.state,
                session.workspaceFolderOf(file),
                session.workspaceChanges,
                binding.editor.host.projectFolder,
                "${spec.id}.environment",
                notNow,
                prompts,
                { project -> spec.environmentProblem(file, project) },
                { session.gateOf(binding.file) },
                runInTerminal,
            )
        return EnvironmentBanner(binding.editor, model)
    }
}
