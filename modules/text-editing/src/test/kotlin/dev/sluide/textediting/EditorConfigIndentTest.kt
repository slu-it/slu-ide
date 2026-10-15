package dev.sluide.textediting

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorConfigIndentTest {
    @TempDir
    lateinit var folder: Path

    private fun editorConfig(
        dir: String,
        content: String,
    ) {
        folder
            .resolve(dir)
            .createDirectories()
            .resolve(".editorconfig")
            .writeText(content.trimIndent())
    }

    @Test
    fun `reads the indentation of matching sections`() {
        editorConfig(
            ".",
            """
            root = true

            [*]
            indent_style = space
            indent_size = 4

            [*.{yml,yaml}]
            indent_size = 2

            [Makefile]
            indent_style = tab
            """,
        )
        assertEquals(EditorConfigIndent(false, 4, 4), EditorConfigIndent.of(folder.resolve("a.kt")))
        assertEquals(EditorConfigIndent(false, 2, 2), EditorConfigIndent.of(folder.resolve("ci.yml")))
        assertEquals(EditorConfigIndent(true, 4, 4), EditorConfigIndent.of(folder.resolve("Makefile")))
    }

    @Test
    fun `lets nested files override outer ones`() {
        editorConfig(".", "root = true\n[*]\nindent_style = space\nindent_size = 4\n")
        editorConfig("sub", "[*.kt]\nindent_size = 2\n")
        assertEquals(EditorConfigIndent(false, 2, 2), EditorConfigIndent.of(folder.resolve("sub/deep/a.kt")))
        assertEquals(EditorConfigIndent(false, 4, 4), EditorConfigIndent.of(folder.resolve("sub/a.java")))
    }

    @Test
    fun `stops at a root file`() {
        editorConfig(".", "root = true\n[*]\nindent_style = space\n")
        editorConfig("outer", "[*]\nindent_size = 8\n")
        editorConfig("outer/project", "root = true\n[*]\nindent_size = 3\n")
        assertEquals(EditorConfigIndent(null, 3, 3), EditorConfigIndent.of(folder.resolve("outer/project/a.kt")))
        assertEquals(EditorConfigIndent(false, 8, 8), EditorConfigIndent.of(folder.resolve("outer/a.kt")))
    }

    @Test
    fun `matches path globs`() {
        editorConfig(".", "root = true\n[src/**.kt]\nindent_size = 2\n")
        assertEquals(2, EditorConfigIndent.of(folder.resolve("src/main/a.kt")).size)
        assertEquals(null, EditorConfigIndent.of(folder.resolve("test/a.kt")).size)
    }

    @Test
    fun `takes the tab width for indent_size tab`() {
        editorConfig(".", "root = true\n[*]\nindent_style = tab\nindent_size = tab\ntab_width = 2\n")
        assertEquals(EditorConfigIndent(true, 2, 2), EditorConfigIndent.of(folder.resolve("a.go")))
    }

    @Test
    fun `keeps a tab width different from the indent size`() {
        editorConfig(".", "root = true\n[*]\nindent_size = 4\ntab_width = 8\n")
        assertEquals(EditorConfigIndent(null, 4, 8), EditorConfigIndent.of(folder.resolve("a.c")))
    }

    @Test
    fun `sets nothing without a matching file or for unset and invalid values`() {
        editorConfig("unset", "root = true\n[*]\nindent_style = unset\nindent_size = many\n")
        editorConfig("none", "root = true\n")
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("unset/a.kt")))
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("none/a.kt")))
    }

    @Test
    fun `chooses the style of a file from editorconfig, then the text, then 4 spaces`() {
        editorConfig("configured", "root = true\n[*]\nindent_style = space\nindent_size = 2\n")
        editorConfig("partial", "root = true\n[*]\nindent_style = space\n")
        editorConfig("none", "root = true\n")
        val tabbed = "a {\n\tb\n}\n"

        assertEquals(IndentStyle(false, 2), IndentStyle.forFile(folder.resolve("configured/a.c"), tabbed))
        assertEquals(IndentStyle(false, 4), IndentStyle.forFile(folder.resolve("partial/a.c"), tabbed))
        assertEquals(IndentStyle(false, 3), IndentStyle.forFile(folder.resolve("partial/a.c"), "a\n   b\n"))
        assertEquals(IndentStyle(true, 4), IndentStyle.forFile(folder.resolve("none/a.c"), tabbed))
        assertEquals(IndentStyle.DEFAULT, IndentStyle.forFile(folder.resolve("none/a.c"), "a\nb\n"))
    }

    @Test
    fun `a language's default comes last`() {
        editorConfig("configured", "root = true\n[*]\nindent_style = space\nindent_size = 2\n")
        editorConfig("none", "root = true\n")
        val tabs = IndentStyle(useTabs = true, size = 4)

        assertEquals(tabs, IndentStyle.forFile(folder.resolve("none/Makefile"), "all:\n", tabs))
        assertEquals(IndentStyle(false, 2), IndentStyle.forFile(folder.resolve("none/Makefile"), "a\n  b\n", tabs))
        assertEquals(IndentStyle(false, 2), IndentStyle.forFile(folder.resolve("configured/Makefile"), "all:\n", tabs))
    }
}
