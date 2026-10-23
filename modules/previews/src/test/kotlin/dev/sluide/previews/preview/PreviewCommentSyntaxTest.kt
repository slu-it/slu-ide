package dev.sluide.previews.preview

import dev.sluide.previews.bicep.BicepFileViewer
import dev.sluide.previews.html.HtmlFileViewer
import dev.sluide.previews.image.SvgFileViewer
import dev.sluide.previews.json.JsonFileViewer
import dev.sluide.previews.markdown.MarkdownFileViewer
import dev.sluide.previews.markdown.MermaidFileViewer
import dev.sluide.previews.notebook.NotebookFileViewer
import dev.sluide.previews.table.CookiesFileViewer
import dev.sluide.previews.table.CsvFileViewer
import dev.sluide.previews.workflow.WorkflowFileViewer
import dev.sluide.previews.xml.XmlFileViewer
import dev.sluide.previews.yaml.YamlFileViewer
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
                YamlFileViewer() to CommentSyntax.HASH,
                NotebookFileViewer() to CommentSyntax.NONE,
                MermaidFileViewer() to CommentSyntax.MERMAID,
                CookiesFileViewer() to CommentSyntax.NONE,
                BicepFileViewer() to CommentSyntax.SLASHES,
                WorkflowFileViewer() to CommentSyntax.HASH,
            )
        for ((viewer, comment) in expected) assertEquals(comment, viewer.comment, viewer::class.simpleName)
    }
}
