package dev.sluide.fileviewers.xml

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.preview.FoldState
import dev.sluide.fileviewers.preview.Preview
import dev.sluide.fileviewers.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Shows XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, IntelliJ's `.iml`) as highlighted source, the side of the
 * XML preview ([XmlPreview], ADR 0065) a [PreviewFileViewer] shows. `.svg`, `.html` and notebooks have their own.
 */
@Component
class XmlFileViewer :
    PreviewFileViewer(
        setOf("xml", "xsd", "xsl", "xslt", "fxml", "iml"),
        RSyntaxHighlighter.xml,
        XmlPreview,
        CommentSyntax.XML,
    )

/**
 * The XML as nested, foldable blocks ([XmlRenderer]), scrolling with the source. Opens side by side with the source,
 * which takes the focus (ADR 0050), so the file can be edited at once. The page's folds are read before it is replaced
 * and passed on, so they survive saving and reloading from disk (a theme change only recolours the page).
 */
internal object XmlPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(XmlRenderer.render(text, dark, FoldState.read(engine)))

    override val syncsScroll get() = true

    override val folds get() = true
}
