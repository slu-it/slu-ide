package dev.sluide.previews.plantuml

import dev.sluide.fileviewers.Highlighters
import dev.sluide.plantuml.PlantUml
import dev.sluide.plantuml.Rendering
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.stage.Window
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

/**
 * Shows PlantUML files (`.puml`, `.plantuml`, `.pu`, `.iuml`) as highlighted source beside their diagrams, drawn by
 * the app's PlantUML engine ([PlantUml.shared], ADR 0193) through [render]; each view gets its own [PlantUmlPreview].
 * Spring uses the constructor without arguments.
 */
@Component
class PlantUmlFileViewer internal constructor(
    private val ask: (Window?, Pair<Int, Int>) -> Pair<Int, Int>? = ::askPngSize,
    private val render: (source: String, firstLine: Int) -> CompletableFuture<Rendering>,
) : PreviewFileViewer(
        setOf("puml", "plantuml", "pu", "iuml"),
        Highlighters.plantuml,
        // Never shown: every view gets its own preview (newPreview).
        PlantUmlPreview(render, ask = ask),
        CommentSyntax.PLANTUML,
    ) {
    constructor() : this(render = { source, firstLine -> PlantUml.shared.render(source, firstLine) })

    override fun newPreview(): Preview = PlantUmlPreview(render, ask = ask)
}
