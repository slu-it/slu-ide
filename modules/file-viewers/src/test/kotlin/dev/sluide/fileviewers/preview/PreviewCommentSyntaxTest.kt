package dev.sluide.fileviewers.preview

import dev.sluide.fileviewers.bicep.BicepFileViewer
import dev.sluide.fileviewers.html.HtmlFileViewer
import dev.sluide.fileviewers.image.SvgFileViewer
import dev.sluide.fileviewers.json.JsonFileViewer
import dev.sluide.fileviewers.markdown.MarkdownFileViewer
import dev.sluide.fileviewers.markdown.MermaidFileViewer
import dev.sluide.fileviewers.notebook.NotebookFileViewer
import dev.sluide.fileviewers.table.CookiesFileViewer
import dev.sluide.fileviewers.table.CsvFileViewer
import dev.sluide.fileviewers.xml.XmlFileViewer
import dev.sluide.textediting.CommentSyntax
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the source of each preview viewer comments out lines (Shortcut+/). Add a row per viewer. */
class PreviewCommentSyntaxTest {
    @Test
    fun `preview viewers comment out lines of their source`() {
        val expected =
            mapOf(
                HtmlFileViewer() to CommentSyntax.XML,
                MarkdownFileViewer() to CommentSyntax.XML,
                SvgFileViewer() to CommentSyntax.XML,
                CsvFileViewer() to CommentSyntax.NONE,
                JsonFileViewer() to CommentSyntax.NONE,
                XmlFileViewer() to CommentSyntax.XML,
                NotebookFileViewer() to CommentSyntax.NONE,
                MermaidFileViewer() to CommentSyntax.MERMAID,
                CookiesFileViewer() to CommentSyntax.NONE,
                BicepFileViewer() to CommentSyntax.SLASHES,
            )
        for ((viewer, comment) in expected) assertEquals(comment, viewer.comment, viewer::class.simpleName)
    }
}
