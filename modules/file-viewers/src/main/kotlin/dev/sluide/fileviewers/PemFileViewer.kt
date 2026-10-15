package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows PEM files (`.pem`, `.crt`, `.cer`, `.key`) as the details of their certificates and keys ([PemRenderer])
 * beside the highlighted text; the details come first. Only files with a `-----BEGIN ` in their first 8 KiB: a
 * binary (DER) certificate is no text file, and a `.key` without PEM text opens as plain text.
 */
@Component
class PemFileViewer : PreviewFileViewer(PEM_EXTENSIONS, RSyntaxHighlighter.pem, PemPreview, CommentSyntax.NONE) {
    override fun canOpen(file: Path): Boolean =
        file.extension.lowercase() in PEM_EXTENSIONS &&
            Files.isReadable(file) &&
            readTextSample(file)?.let { "-----BEGIN " in String(it, Charsets.UTF_8) } == true
}

private val PEM_EXTENSIONS = setOf("pem", "crt", "cer", "key")

/** The details page, no scroll sync (blocks are no lines). */
internal object PemPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.loadContent(PemRenderer.render(text, dark))

    override val defaultMode get() = PreviewMode.PREVIEW
}
