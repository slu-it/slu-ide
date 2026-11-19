package dev.sluide.textediting

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `takes the tab width as size for indent_style tab without an indent size`() {
        editorConfig(".", "root = true\n[*]\nindent_style = tab\ntab_width = 3\n")
        assertEquals(EditorConfigIndent(true, 3, 3), EditorConfigIndent.of(folder.resolve("a.go")))
    }

    @Test
    fun `reads keys and values in any case and with surrounding blanks`() {
        // The EditorConfig specification: keys and values of the supported properties are case insensitive.
        editorConfig(".", "root = true\n[*]\nINDENT_STYLE =  TAB \nIndent_Size = Tab\nTab_Width =  3 \n")
        assertEquals(EditorConfigIndent(true, 3, 3), EditorConfigIndent.of(folder.resolve("a.go")))
    }

    @Test
    fun `completes the size from the tab width only for indent_size tab or indent_style tab`() {
        editorConfig("sizeTab", "root = true\n[*]\nindent_style = space\nindent_size = tab\ntab_width = 3\n")
        editorConfig("sizeTabAlone", "root = true\n[*]\nindent_size = tab\n")
        editorConfig("space", "root = true\n[*]\nindent_style = space\ntab_width = 3\n")
        editorConfig("width", "root = true\n[*]\ntab_width = 3\n")
        editorConfig("tabAlone", "root = true\n[*]\nindent_style = tab\n")
        assertEquals(EditorConfigIndent(false, 3, 3), EditorConfigIndent.of(folder.resolve("sizeTab/a.c")))
        assertEquals(EditorConfigIndent(null, null, null), EditorConfigIndent.of(folder.resolve("sizeTabAlone/a.c")))
        assertEquals(EditorConfigIndent(false, null, 3), EditorConfigIndent.of(folder.resolve("space/a.c")))
        assertEquals(EditorConfigIndent(null, null, 3), EditorConfigIndent.of(folder.resolve("width/a.c")))
        assertEquals(EditorConfigIndent(true, null, null), EditorConfigIndent.of(folder.resolve("tabAlone/a.c")))
    }

    @Test
    fun `ignores a size or tab width that is not positive`() {
        editorConfig("zero", "root = true\n[*]\nindent_size = 0\ntab_width = 0\n")
        editorConfig("negative", "root = true\n[*]\nindent_size = -2\ntab_width = -4\n")
        editorConfig("fallback", "root = true\n[*]\nindent_size = 2\ntab_width = 0\n")
        editorConfig("text", "root = true\n[*]\nindent_size = 2\ntab_width = wide\n")
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("zero/a.c")))
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("negative/a.c")))
        assertEquals(EditorConfigIndent(null, 2, 2), EditorConfigIndent.of(folder.resolve("fallback/a.c")))
        assertEquals(EditorConfigIndent(null, 2, 2), EditorConfigIndent.of(folder.resolve("text/a.c")))
        assertEquals(
            EditorConfigIndent(null, 1, 1),
            run {
                editorConfig("one", "root = true\n[*]\nindent_size = 1\n")
                EditorConfigIndent.of(folder.resolve("one/a.c"))
            },
        )
    }

    @Test
    fun `sets nothing for an invalid or unreadable editorconfig`() {
        editorConfig("invalid", "[*\nindent_size = 2\n")
        folder.resolve("directory/.editorconfig").createDirectories()
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("invalid/a.kt")))
        assertEquals(EditorConfigIndent(), EditorConfigIndent.of(folder.resolve("directory/a.kt")))
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
    fun `the tab width of a file comes from editorconfig, else from the size`() {
        editorConfig("both", "root = true\n[*]\nindent_size = 2\ntab_width = 8\n")
        editorConfig("width", "root = true\n[*]\nindent_style = space\ntab_width = 8\n")
        editorConfig("none", "root = true\n")

        assertEquals(IndentStyle(false, 2, 8), IndentStyle.forFile(folder.resolve("both/a.c"), "a\n   b\n"))
        assertEquals(IndentStyle(false, 3, 8), IndentStyle.forFile(folder.resolve("width/a.c"), "a\n   b\n"))
        assertEquals(IndentStyle(false, 3, 3), IndentStyle.forFile(folder.resolve("none/a.c"), "a\n   b\n"))
    }

    @Test
    fun `a style needs a positive size and tab width`() {
        assertEquals(1, IndentStyle(false, 1, 1).size)
        val size = assertFailsWith<IllegalArgumentException> { IndentStyle(false, 0, 4) }
        assertEquals("Indent size and tab width must be positive: 0, 4", size.message)
        val width = assertFailsWith<IllegalArgumentException> { IndentStyle(true, 4, 0) }
        assertEquals("Indent size and tab width must be positive: 4, 0", width.message)
        assertFailsWith<IllegalArgumentException> { IndentStyle(true, -1, -1) }
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
