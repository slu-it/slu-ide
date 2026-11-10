package dev.sluide.codeintelligence.workspace

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.collections.ObservableMap
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.nio.file.Path

/**
 * The configuration problems banners of a bound editor (ADR 0160), looking like the environment banner: per config
 * file with problems a title, a link per problem (opens the file at its place through [openFile]), "… and N more", and
 * Not Now. FX thread.
 *
 * @param openFile opens a file with the caret at a 0-based line and column.
 */
internal class ConfigProblemsBanner(
    private val editor: ExtensibleCodeEditor,
    private val model: ConfigProblemsModel,
    private val openFile: (file: Path, line: Int, column: Int) -> Unit,
) : AutoCloseable {
    private var handles = emptyList<AutoCloseable>()
    private var shown = emptyList<ConfigProblems>()
    private val subscription = model.shown.subscribe { problems -> render(problems) }

    private fun render(problems: List<ConfigProblems>) {
        if (problems == shown) return
        shown = problems
        handles.forEach { it.close() }
        handles = problems.map { editor.addBanner(bannerOf(it)) }
    }

    private fun bannerOf(problems: ConfigProblems): HBox {
        val lines =
            VBox(
                Label(problems.title).apply {
                    isWrapText = true
                    minWidth = 0.0
                },
            )
        problems.listed.forEach { problem ->
            lines.children +=
                Hyperlink(problem.text).apply {
                    styleClass += "banner-link"
                    isFocusTraversable = false
                    setOnAction {
                        isVisited = false
                        openFile(problems.file, problem.line, problem.column)
                    }
                }
        }
        if (problems.more > 0) lines.children += Label("… and ${problems.more} more")
        return HBox(
            lines,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            Button("Not Now").apply {
                styleClass += "code-intelligence-config-not-now"
                isFocusTraversable = false
                minWidth = Region.USE_PREF_SIZE
                setOnAction { model.notNow(problems) }
            },
        ).apply {
            styleClass += listOf("editor-banner", "code-intelligence-banner", "code-intelligence-config-problems")
            alignment = Pos.CENTER_LEFT
        }
    }

    override fun close() {
        subscription.unsubscribe()
        handles.forEach { it.close() }
        handles = emptyList()
        model.close()
    }
}

/**
 * The configuration problems banner as a feature of every binding (always attached, it shows only while the document
 * is served), with the window's Not Now answers ([notNow], by config file); a link opens the file through the
 * editor's host.
 */
internal class ConfigProblemsFeature(
    private val notNow: ObservableMap<Path, List<ConfigProblem>>,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val session = binding.session
        val spec = binding.spec
        val host = binding.editor.host
        val project = host.projectFolder
        val model =
            ConfigProblemsModel(
                spec.name,
                project,
                { if (project == null) emptyList() else spec.configFiles(binding.file, project) },
                session::keptDiagnostics,
                { session.gateOf(binding.file)?.served == true },
                listOf(session.workspaceChanges, session.keptDiagnosticsChanges),
                notNow,
            )
        return ConfigProblemsBanner(binding.editor, model) { file, line, column -> host.openFile(file, line, column) }
    }
}
