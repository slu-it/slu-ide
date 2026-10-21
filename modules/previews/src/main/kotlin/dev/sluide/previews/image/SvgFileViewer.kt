package dev.sluide.previews.image

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.previews.preview.PreviewMode
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Shows SVG images (`.svg`) as highlighted XML beside the rendered image ([ImagePage]); the image comes first. */
@Component
class SvgFileViewer : PreviewFileViewer(setOf("svg"), Highlighters.xml, SvgPreview, CommentSyntax.XML)

/** The SVG as an image, so that its scripts never run; exported as PNG at 100, 200 and 300% ([SvgPngExport]). */
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

    override val exports by lazy { listOf(1, 2, 3).map { SvgPngExport(it) } }
}
