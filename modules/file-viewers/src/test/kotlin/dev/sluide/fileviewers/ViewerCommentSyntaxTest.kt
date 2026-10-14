package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the source of each viewer comments out lines (Shortcut+/). Add a row per viewer. */
class ViewerCommentSyntaxTest {
    @Test
    fun `code viewers comment out lines in their language`() {
        val expected =
            mapOf(
                PythonFileViewer() to CommentSyntax.HASH,
                YamlFileViewer() to CommentSyntax.HASH,
                KotlinFileViewer() to CommentSyntax.SLASHES,
                JavaFileViewer() to CommentSyntax.SLASHES,
                JavaScriptFileViewer() to CommentSyntax.SLASHES,
                TypeScriptFileViewer() to CommentSyntax.SLASHES,
                JsxFileViewer() to CommentSyntax.SLASHES,
                TsxFileViewer() to CommentSyntax.SLASHES,
                ScssFileViewer() to CommentSyntax.SLASHES,
                LessFileViewer() to CommentSyntax.SLASHES,
                SassFileViewer() to CommentSyntax.SLASHES,
                CssFileViewer() to CommentSyntax.CSS,
                XmlFileViewer() to CommentSyntax.XML,
                MdxFileViewer() to CommentSyntax.XML,
                SingleFileComponentFileViewer() to CommentSyntax.XML,
                JsonFileViewer() to CommentSyntax.NONE,
            )
        for ((viewer, comment) in expected) assertEquals(comment, viewer.comment, viewer::class.simpleName)
    }

    @Test
    fun `preview viewers comment out lines of their source`() {
        val expected =
            mapOf(
                HtmlFileViewer() to CommentSyntax.XML,
                MarkdownFileViewer() to CommentSyntax.XML,
                SvgFileViewer() to CommentSyntax.XML,
                CsvFileViewer() to CommentSyntax.NONE,
                NotebookFileViewer() to CommentSyntax.NONE,
            )
        for ((viewer, comment) in expected) assertEquals(comment, viewer.comment, viewer::class.simpleName)
    }
}
