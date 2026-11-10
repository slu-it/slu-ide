package dev.sluide.previews.preview

import dev.sluide.fileviewers.ContentFileViewer
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileContent
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.SourceFileViewer
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.isTextFile
import dev.sluide.textediting.CommentSyntax
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Read-only views of text files with one of the [fileExtensions] (case-insensitive) as highlighted source beside a
 * rendered [preview] ([PreviewFileView], ADR 0016), the source commenting out lines with [comment]; see
 * `MarkdownFileViewer`.
 */
abstract class PreviewFileViewer internal constructor(
    final override val fileExtensions: Set<String>,
    internal val highlighter: SyntaxHighlighter,
    private val preview: Preview,
    internal val comment: CommentSyntax = CommentSyntax.NONE,
) : SourceFileViewer,
    ExtensionFileViewer,
    ContentFileViewer {
    /** The source is highlighted like the previews show it, and so are its diffs. */
    final override val sourceHighlighter: SyntaxHighlighter get() = highlighter

    override fun canOpen(file: Path): Boolean = matchesExtension(file) && Files.isReadable(file) && isTextFile(file)

    /** Whether the extension of [file] is one of this viewer's; the file is not looked at. */
    internal fun matchesExtension(file: Path): Boolean = file.extension.lowercase() in fileExtensions

    /**
     * The preview for a new view; a viewer whose preview keeps per-view state ([Preview.attach]) returns a new one
     * for each view.
     */
    internal open fun newPreview(): Preview = preview

    override fun open(file: Path): FileView = PreviewFileView(file, highlighter, newPreview(), comment)

    /**
     * Whether this viewer shows content of a file named [name] ([ContentFileViewer]); no by default, as most previews
     * read the shown path themselves (HTML is served from it). Markdown, JSON and YAML say yes by [hasExtension].
     */
    override fun opensContent(name: String): Boolean = false

    /** Whether the extension of the file [name] is one of this viewer's; nothing is looked at. */
    protected fun hasExtension(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in fileExtensions

    override fun open(
        file: Path,
        content: FileContent,
    ): FileView {
        require(opensContent(file.fileName.toString())) { "${javaClass.simpleName} does not show content" }
        return PreviewFileView(file, highlighter, newPreview(), comment, content)
    }
}
