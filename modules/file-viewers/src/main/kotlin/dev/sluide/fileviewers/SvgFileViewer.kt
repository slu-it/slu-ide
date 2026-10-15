package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shows SVG images (`.svg`) as highlighted XML beside the rendered image ([ImagePage]); the image comes first. */
@Component
class SvgFileViewer : PreviewFileViewer(setOf("svg"), RSyntaxHighlighter.xml, SvgPreview, CommentSyntax.XML)

/** The SVG as an image, so that its scripts never run. */
internal object SvgPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(ImagePage.render(text.toByteArray(), "image/svg+xml", dark))

    override val defaultMode get() = PreviewMode.PREVIEW

    override val zooms get() = true
}
