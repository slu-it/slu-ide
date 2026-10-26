package dev.sluide.fileviewers

import dev.sluide.fileviewers.bicep.BicepParamFileViewer
import dev.sluide.fileviewers.languages.CaddyfileFileViewer
import dev.sluide.fileviewers.languages.CodeownersFileViewer
import dev.sluide.fileviewers.languages.ConfigFileViewer
import dev.sluide.fileviewers.languages.CssFileViewer
import dev.sluide.fileviewers.languages.DockerfileFileViewer
import dev.sluide.fileviewers.languages.GitBlameIgnoreRevsFileViewer
import dev.sluide.fileviewers.languages.GitattributesFileViewer
import dev.sluide.fileviewers.languages.GroovyFileViewer
import dev.sluide.fileviewers.languages.IgnoreFileViewer
import dev.sluide.fileviewers.languages.IniFileViewer
import dev.sluide.fileviewers.languages.JavaFileViewer
import dev.sluide.fileviewers.languages.JavaScriptFileViewer
import dev.sluide.fileviewers.languages.Json5FileViewer
import dev.sluide.fileviewers.languages.JsonLinesFileViewer
import dev.sluide.fileviewers.languages.JsxFileViewer
import dev.sluide.fileviewers.languages.KotlinFileViewer
import dev.sluide.fileviewers.languages.KqlFileViewer
import dev.sluide.fileviewers.languages.LessFileViewer
import dev.sluide.fileviewers.languages.MailmapFileViewer
import dev.sluide.fileviewers.languages.MakefileFileViewer
import dev.sluide.fileviewers.languages.MdxFileViewer
import dev.sluide.fileviewers.languages.PowerShellFileViewer
import dev.sluide.fileviewers.languages.PropertiesFileViewer
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.languages.SassFileViewer
import dev.sluide.fileviewers.languages.ScssFileViewer
import dev.sluide.fileviewers.languages.ShellFileViewer
import dev.sluide.fileviewers.languages.SingleFileComponentFileViewer
import dev.sluide.fileviewers.languages.SqlFileViewer
import dev.sluide.fileviewers.languages.SshConfigFileViewer
import dev.sluide.fileviewers.languages.TomlFileViewer
import dev.sluide.fileviewers.languages.TsxFileViewer
import dev.sluide.fileviewers.languages.TypeScriptFileViewer
import dev.sluide.textediting.CommentSyntax
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How each code viewer comments out lines (Shortcut+/); the sources of the previews: PreviewCommentSyntaxTest. Add a
 * row per viewer.
 */
class ViewerCommentSyntaxTest {
    @Test
    fun `code viewers comment out lines in their language`() {
        val expected =
            mapOf(
                PythonFileViewer() to CommentSyntax.HASH,
                KotlinFileViewer() to CommentSyntax.SLASHES,
                GroovyFileViewer() to CommentSyntax.SLASHES,
                JavaFileViewer() to CommentSyntax.SLASHES,
                JavaScriptFileViewer() to CommentSyntax.SLASHES,
                TypeScriptFileViewer() to CommentSyntax.SLASHES,
                JsxFileViewer() to CommentSyntax.SLASHES,
                TsxFileViewer() to CommentSyntax.SLASHES,
                ScssFileViewer() to CommentSyntax.SLASHES,
                LessFileViewer() to CommentSyntax.SLASHES,
                SassFileViewer() to CommentSyntax.SLASHES,
                CssFileViewer() to CommentSyntax.CSS,
                MdxFileViewer() to CommentSyntax.XML,
                SingleFileComponentFileViewer() to CommentSyntax.XML,
                SqlFileViewer() to CommentSyntax.SQL,
                ShellFileViewer() to CommentSyntax.HASH,
                PowerShellFileViewer() to CommentSyntax.POWERSHELL,
                DockerfileFileViewer() to CommentSyntax.HASH,
                MakefileFileViewer() to CommentSyntax.HASH,
                IniFileViewer() to CommentSyntax.SEMICOLON,
                ConfigFileViewer() to CommentSyntax.HASH,
                CodeownersFileViewer() to CommentSyntax.HASH,
                GitattributesFileViewer() to CommentSyntax.HASH,
                MailmapFileViewer() to CommentSyntax.HASH,
                Json5FileViewer() to CommentSyntax.SLASHES,
                JsonLinesFileViewer() to CommentSyntax.NONE,
                PropertiesFileViewer() to CommentSyntax.HASH,
                IgnoreFileViewer() to CommentSyntax.HASH,
                GitBlameIgnoreRevsFileViewer() to CommentSyntax.HASH,
                SshConfigFileViewer() to CommentSyntax.HASH,
                CaddyfileFileViewer() to CommentSyntax.HASH,
                TomlFileViewer() to CommentSyntax.HASH,
                BicepParamFileViewer() to CommentSyntax.SLASHES,
                KqlFileViewer() to CommentSyntax.LINE_SLASHES,
            )
        for ((viewer, comment) in expected) assertEquals(comment, viewer.comment, viewer::class.simpleName)
    }
}
