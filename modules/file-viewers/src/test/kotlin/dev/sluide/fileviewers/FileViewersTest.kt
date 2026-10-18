package dev.sluide.fileviewers

import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileViewersTest {
    @TempDir
    lateinit var dir: Path

    private val fallback = DefaultTextFileViewer()

    private class ExtensionViewer(
        private val extension: String,
    ) : FileViewer {
        override fun canOpen(file: Path) = file.extension == extension

        override fun open(file: Path): FileView = error("not needed")
    }

    /** Opens files named [name] and scripts whose shebang names [interpreter]. */
    private class ScriptViewer(
        private val name: String,
        private val interpreter: String,
    ) : ShebangViewer {
        override fun canOpen(file: Path) = file.name == name

        override fun opensInterpreter(interpreter: String) = interpreter == this.interpreter

        override fun open(file: Path): FileView = error("not needed")
    }

    @Test
    fun `a viewer opening a file by its name wins over one opening it by its shebang`() {
        val python = PythonFileViewer()
        val build = ScriptViewer("build", "make")
        val script = dir.resolve("build").apply { writeText("#!/usr/bin/env python3\n") }

        assertSame(build, FileViewers(listOf(python, build), fallback).viewerFor(script))
        assertSame(build, FileViewers(listOf(build, python), fallback).viewerFor(script))
    }

    /** A code viewer opening files by the given [names], [extensions] and [patterns]. */
    private class NamesViewer(
        extensions: Set<String> = emptySet(),
        names: Set<String> = emptySet(),
        patterns: List<Regex> = emptyList(),
    ) : CodeFileViewer(extensions, RSyntaxHighlighter.java, names = names, namePatterns = patterns)

    @Test
    fun `an exact name wins over an extension, an extension over a name pattern, in any order`() {
        val name = NamesViewer(names = setOf("notes.txt"))
        val extension = NamesViewer(extensions = setOf("txt"))
        val pattern = NamesViewer(patterns = listOf(Regex("""notes\..*""")))
        val notes = dir.resolve("notes.txt").apply { writeText("x") }
        val orders = listOf(listOf(name, extension, pattern), listOf(pattern, extension, name))

        for (order in orders) {
            assertSame(name, FileViewers(order, fallback).viewerFor(notes))
            assertSame(extension, FileViewers(order - name, fallback).viewerFor(notes))
            assertSame(pattern, FileViewers(listOf(pattern), fallback).viewerFor(notes))
        }
    }

    @Test
    fun `names like Dockerfile dot something open by their extension if a viewer takes it`() {
        val viewers =
            listOf(
                DockerfileFileViewer(),
                CaddyfileFileViewer(),
                IgnoreFileViewer(),
                MarkdownFileViewer(),
                JsonFileViewer(),
            )
        val expected =
            mapOf(
                "Dockerfile.md" to MarkdownFileViewer::class,
                "Caddyfile.json" to JsonFileViewer::class,
                "Dockerfile.dev" to DockerfileFileViewer::class,
                "Dockerfile.prod" to DockerfileFileViewer::class,
                "Caddyfile.dev" to CaddyfileFileViewer::class,
                "Dockerfile.dockerignore" to IgnoreFileViewer::class,
                "Dockerfile.dev.dockerignore" to IgnoreFileViewer::class,
            )

        for (order in listOf(viewers, viewers.reversed())) {
            for ((name, viewer) in expected) {
                val file = dir.resolve(name).apply { writeText("x\n") }
                assertEquals(viewer, FileViewers(order, fallback).viewerFor(file)?.let { it::class }, name)
            }
        }
    }

    @Test
    fun `the first viewer opening the interpreter of the shebang wins`() {
        val first = ScriptViewer("a", "node")
        val second = ScriptViewer("b", "node")
        val script = dir.resolve("tool").apply { writeText("#!/usr/bin/env node\n") }

        assertSame(first, FileViewers(listOf(first, second), fallback).viewerFor(script))
        assertSame(second, FileViewers(listOf(second, first), fallback).viewerFor(script))
    }

    @Test
    fun `files with an extension, dotfiles included, are not opened by their shebang`() {
        val bash = ScriptViewer("none", "bash")
        val viewers = FileViewers(listOf(bash), fallback)

        assertSame(fallback, viewers.viewerFor(dir.resolve(".bashrc").apply { writeText("#!/bin/bash\n") }))
        assertSame(fallback, viewers.viewerFor(dir.resolve("run.sh.bak").apply { writeText("#!/bin/bash\n") }))
        assertSame(bash, viewers.viewerFor(dir.resolve("run").apply { writeText("#!/bin/bash\n") }))
        assertNull(viewers.viewerFor(dir.resolve("blob").apply { writeBytes("#!/bin/bash\n".toByteArray() + 0x00) }))
        assertNull(viewers.viewerFor(dir.resolve("missing")))
    }

    @Test
    fun `specific viewer wins over the fallback`() {
        val json = ExtensionViewer("json")
        val file = dir.resolve("a.json").apply { writeText("{}") }

        assertSame(json, FileViewers(listOf(json, fallback), fallback).viewerFor(file))
    }

    @Test
    fun `fallback is asked last even when listed first`() {
        val json = ExtensionViewer("json")
        val viewers = FileViewers(listOf(fallback, json), fallback)

        assertEquals(listOf(json, fallback), viewers.viewers)
        assertSame(json, viewers.viewerFor(dir.resolve("a.json").apply { writeText("{}") }))
    }

    @Test
    fun `fallback opens text files no specific viewer accepts`() {
        val file = dir.resolve("notes.txt").apply { writeText("hello") }

        assertSame(fallback, FileViewers(listOf(ExtensionViewer("json")), fallback).viewerFor(file))
    }

    @Test
    fun `no viewer for binary files`() {
        val file = dir.resolve("image.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00)) }

        assertNull(FileViewers(listOf(fallback), fallback).viewerFor(file))
    }

    @Test
    fun `only text viewers show diffs`() {
        val file = dir.resolve("a.txt").apply { writeText("a\n") }

        assertNull(FileViewers(listOf(ExtensionViewer("txt")), fallback).openDiff(file, TextDiff("", emptyList())))
    }

    @Test
    fun `source viewers of other modules show diffs`() {
        val source =
            object : SourceFileViewer {
                override val sourceHighlighter = Highlighters.forLanguage("json")

                override fun canOpen(file: Path) = file.extension == "http"

                override fun open(file: Path): FileView = error("not needed")
            }
        val viewers = FileViewers(listOf(source, ExtensionViewer("txt")), fallback)

        assertTrue(viewers.showsDiffs(source))
        assertFalse(viewers.showsDiffs(ExtensionViewer("txt")))
    }

    @Test
    fun `the viewer for a diff of given texts is picked by the name alone, without the file`() {
        val kotlin = KotlinFileViewer()
        val markdown = MarkdownFileViewer()
        val image = ImageFileViewer()
        val python = PythonFileViewer()
        val viewers = FileViewers(listOf(image, markdown, kotlin, python), fallback)

        // None of these files exists.
        fun viewer(
            name: String,
            text: String = "",
        ) = viewers.viewerByName(dir.resolve(name), text)

        assertSame(kotlin, viewer("src/Main.kt"))
        assertSame(markdown, viewer("README.MD"))
        assertSame(image, viewer("logo.png"))
        assertSame(fallback, viewer("notes.txt"))
        assertSame(fallback, viewer("Makefile.unknown"))
        assertSame(python, viewer("run", "#!/usr/bin/env python3\nprint()\n"))
        assertSame(fallback, viewer("run", "echo\n"))
        assertSame(fallback, viewer("run.sh", "#!/usr/bin/env python3\n"), "only names without an extension")
        assertSame(fallback, viewer("empty"))
    }

    @Test
    fun `a diff of given texts is not shown for images`() {
        val viewers = FileViewers(listOf(ImageFileViewer()), fallback)

        assertNull(viewers.openDiff(dir.resolve("logo.png"), "", TextDiff("", emptyList())))
    }
}
