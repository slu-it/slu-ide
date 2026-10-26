package dev.sluide.previews.xml

import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.readTextSample
import dev.sluide.previews.preview.FoldState
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, IntelliJ's `.iml`, `.plist`, .NET's `.csproj`, `.fsproj`,
 * `.vbproj`, `.props`, `.targets`, `.xaml`, `.resx`, `.nuspec`, and `.wsdl`) as highlighted source, the side of the
 * XML preview ([XmlPreview], ADR 0065) a [PreviewFileViewer] shows. `.svg`, `.html` and notebooks have their own.
 * `.config` files (`web.config`, `App.config`) open here only when their text starts with `<`: the extension alone is
 * not enough, so it is no [fileExtensions] entry: commit diffs go by name only, so `.config` files show there as plain
 * text.
 */
@Component
class XmlFileViewer :
    PreviewFileViewer(
        setOf(
            "xml",
            "xsd",
            "xsl",
            "xslt",
            "fxml",
            "iml",
            "plist",
            "csproj",
            "fsproj",
            "vbproj",
            "props",
            "targets",
            "xaml",
            "resx",
            "nuspec",
            "wsdl",
        ),
        Highlighters.xml,
        XmlPreview,
        CommentSyntax.XML,
    ) {
    override fun canOpen(file: Path): Boolean =
        if (file.extension.lowercase() == "config") {
            Files.isReadable(file) && readTextSample(file)?.let(::startsWithTag) == true
        } else {
            super.canOpen(file)
        }

    /** Whether the [sample] starts with `<`, after an optional UTF-8 BOM and whitespace. */
    private fun startsWithTag(sample: ByteArray): Boolean =
        String(sample, Charsets.UTF_8).removePrefix("\uFEFF").trimStart().startsWith('<')
}

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

    override val copiesFormatted get() = true

    override fun formatted(text: String) = XmlText.format(text)
}
