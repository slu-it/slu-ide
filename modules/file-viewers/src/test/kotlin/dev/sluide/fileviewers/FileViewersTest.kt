package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.highlight.RSyntaxHighlighter
import dev.sluide.fileviewers.languages.CaddyfileFileViewer
import dev.sluide.fileviewers.languages.DockerfileFileViewer
import dev.sluide.fileviewers.languages.IgnoreFileViewer
import dev.sluide.fileviewers.languages.KotlinFileViewer
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.languages.ShellFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
    fun `names like Dockerfile dot something open by their extension if a viewer of another kind takes it`() {
        val dockerfile = DockerfileFileViewer()
        val caddyfile = CaddyfileFileViewer()
        val ignore = IgnoreFileViewer()
        val markdown = ExtensionsStub(setOf("md"))
        val json = ExtensionsStub(setOf("json"))
        val viewers = listOf(dockerfile, caddyfile, ignore, markdown, json)
        val expected =
            mapOf(
                "Dockerfile.md" to markdown,
                "Caddyfile.json" to json,
                "Dockerfile.dev" to dockerfile,
                "Dockerfile.prod" to dockerfile,
                "Caddyfile.dev" to caddyfile,
                "Dockerfile.dockerignore" to ignore,
                "Dockerfile.dev.dockerignore" to ignore,
            )

        for (order in listOf(viewers, viewers.reversed())) {
            for ((name, viewer) in expected) {
                val file = dir.resolve(name).apply { writeText("x\n") }
                assertSame(viewer, FileViewers(order, fallback).viewerFor(file), name)
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
    fun `the diff of a source viewer is highlighted by its source highlighter`() {
        val source =
            object : SourceFileViewer {
                override val sourceHighlighter = Highlighters.forLanguage("json")

                override fun canOpen(file: Path) = file.extension == "req"

                override fun open(file: Path): FileView = error("not needed")
            }
        val viewers = FileViewers(listOf(source), fallback)

        assertSame(source.sourceHighlighter, viewers.highlighterOf(source))
        assertSame(KotlinFileViewer().highlighter, viewers.highlighterOf(KotlinFileViewer()))
        assertNull(viewers.highlighterOf(ExtensionViewer("txt")))
    }

    @Test
    fun `the viewer for a diff of given texts is picked by the name alone, without the file`() {
        val kotlin = KotlinFileViewer()
        val markdown = ExtensionsStub(setOf("md", "markdown"))
        val image = ExtensionsStub(setOf("png", "jpg"))
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

    /** A viewer of another module for files with the [fileExtensions], e.g. images. */
    private open class ExtensionsStub(
        override val fileExtensions: Set<String>,
    ) : ExtensionFileViewer {
        override fun canOpen(file: Path) = file.extension.lowercase() in fileExtensions

        override fun open(file: Path): FileView = error("not needed")
    }

    @Test
    fun `a viewer for extensions is picked by the name alone, ignoring case`() {
        val images = ExtensionsStub(setOf("png", "jpg"))
        val viewers = FileViewers(listOf(images, KotlinFileViewer()), fallback)

        // None of these files exists.
        assertSame(images, viewers.viewerByName(dir.resolve("logo.png"), ""))
        assertSame(images, viewers.viewerByName(dir.resolve("LOGO.JPG"), ""))
        assertSame(fallback, viewers.viewerByName(dir.resolve("logo.gif"), ""))
        assertNull(viewers.openDiff(dir.resolve("logo.png"), "", TextDiff("", emptyList())), "not shown as text")
    }

    /** A source viewer of another module for the HTTP client's environment files, which are JSON. */
    private class EnvironmentViewer :
        NamedFileViewer,
        SourceFileViewer {
        override val fileNames = setOf("http-client.env.json")
        override val sourceHighlighter = Highlighters.forLanguage("json")

        override fun canOpen(file: Path) = file.name.equals("http-client.env.json", ignoreCase = true)

        override fun open(file: Path): FileView = error("not needed")
    }

    /** A viewer of another module showing JSON files as text, like the JSON preview. */
    private class JsonStub :
        ExtensionsStub(setOf("json")),
        SourceFileViewer {
        override val sourceHighlighter = Highlighters.json
    }

    @Test
    fun `a named viewer wins over a viewer for the extension for its file names, in any order, ignoring case`() {
        val named = EnvironmentViewer()
        val json = JsonStub()
        val env = dir.resolve("http-client.env.json").apply { writeText("{}") }
        // In a folder of its own, for case-insensitive file systems.
        val upper =
            dir
                .resolve("sub")
                .createDirectories()
                .resolve("HTTP-Client.Env.json")
                .apply { writeText("{}") }
        val other = dir.resolve("other.json").apply { writeText("{}") }

        for (order in listOf(listOf(named, json), listOf(json, named))) {
            val viewers = FileViewers(order, fallback)
            assertSame(named, viewers.viewerFor(env))
            assertSame(named, viewers.viewerFor(upper))
            assertSame(json, viewers.viewerFor(other))
            assertSame(named, viewers.viewerByName(dir.resolve("http-client.env.json"), ""))
            assertSame(json, viewers.viewerByName(dir.resolve("other.json"), ""))
            assertTrue(viewers.showsDiffs(named))
        }
    }

    /** A viewer of another module for JSON files and Pipenv's lock file, shown as text, like the JSON preview. */
    private class NamedJsonStub :
        ExtensionsStub(setOf("json")),
        NamedFileViewer,
        SourceFileViewer {
        override val fileNames = setOf("Pipfile.lock")
        override val sourceHighlighter = Highlighters.json
    }

    @Test
    fun `a viewer for names and extensions is picked by either, ignoring case, and shows diffs`() {
        val json = NamedJsonStub()
        val viewers = FileViewers(listOf(json, KotlinFileViewer()), fallback)

        // None of these files exists.
        assertSame(json, viewers.viewerByName(dir.resolve("pipfile.lock"), ""))
        assertSame(json, viewers.viewerByName(dir.resolve("a.json"), ""))
        assertSame(fallback, viewers.viewerByName(dir.resolve("other.lock"), ""))
        assertTrue(viewers.showsDiffs(json))
    }

    /** A source viewer of another module for shell scripts, like the shell-scripts module's. */
    private class ScriptSourceStub :
        ExtensionsStub(setOf("sh", "bash", "zsh", "ksh")),
        SourceFileViewer,
        ShebangViewer {
        override val sourceHighlighter = Highlighters.shell

        override fun opensInterpreter(interpreter: String) = interpreter == "bash"
    }

    @Test
    fun `a diff of a script is picked by its name for the script viewer, of a startup file for the shell viewer`() {
        val shell = ShellFileViewer()
        val scripts = ScriptSourceStub()
        for (order in listOf(listOf(shell, scripts), listOf(scripts, shell))) {
            val viewers = FileViewers(order, fallback)

            // None of these files exists.
            assertSame(scripts, viewers.viewerByName(dir.resolve("deploy.sh"), ""))
            assertSame(scripts, viewers.viewerByName(dir.resolve("deploy"), "#!/bin/bash\n"))
            assertSame(shell, viewers.viewerByName(dir.resolve(".bashrc"), "#!/bin/bash\n"))
            assertSame(Highlighters.shell, viewers.highlighterOf(viewers.viewerByName(dir.resolve("x.sh"), "")))
        }
    }

    /** A viewer of another module for YAML files directly in `.github/workflows`, like the workflow graph. */
    private class WorkflowStub :
        ExtensionsStub(setOf("yml", "yaml")),
        PathFileViewer {
        override fun matchesPath(file: Path) = isInFolders(file, ".github", "workflows")
    }

    @Test
    fun `a path viewer wins over a code viewer of the extension only at its path, in any order`() {
        val workflow = WorkflowStub()
        val yaml = NamesViewer(extensions = setOf("yml", "yaml"))
        val at =
            dir
                .resolve(".github/workflows")
                .createDirectories()
                .resolve("ci.yml")
                .apply { writeText("on: push") }
        val elsewhere = listOf(".github/ci.yml", "workflows/ci.yml", "config.yml", "x/.github/workflows/sub/ci.yml")
        val files = elsewhere.map { dir.resolve(it).apply { parent.createDirectories() }.apply { writeText("a: 1") } }

        for (order in listOf(listOf(workflow, yaml), listOf(yaml, workflow))) {
            val viewers = FileViewers(order, fallback)
            assertSame(workflow, viewers.viewerFor(at))
            files.forEach { assertSame(yaml, viewers.viewerFor(it), it.toString()) }
            assertSame(workflow, viewers.viewerByName(Path.of(".github/workflows/ci.yaml"), ""))
            assertSame(workflow, viewers.viewerByName(Path.of("/repo/.github/workflows/ci.yml"), ""))
            elsewhere.forEach { assertSame(yaml, viewers.viewerByName(Path.of(it), ""), it) }
            assertSame(yaml, viewers.viewerByName(Path.of("a.yml"), ""))
        }
    }

    @Test
    fun `a path viewer is not picked for other kinds of files at its path`() {
        val viewers = FileViewers(listOf(WorkflowStub()), fallback)
        val folder = dir.resolve(".github/workflows").createDirectories()

        for (name in listOf("README.md", "dependabot.json")) {
            val file = folder.resolve(name).apply { writeText("x") }
            assertSame(fallback, viewers.viewerFor(file), name)
            assertSame(fallback, viewers.viewerByName(Path.of(".github/workflows/$name"), ""), name)
        }
    }

    /** A path viewer that would also take scripts naming python by their shebang. */
    private class ScriptPathStub :
        PathFileViewer,
        ShebangViewer {
        override fun matchesPath(file: Path) = isInFolders(file, ".github", "workflows")

        override fun canOpen(file: Path) = true

        override fun opensInterpreter(interpreter: String) = interpreter == "python3"

        override fun open(file: Path): FileView = error("not needed")
    }

    @Test
    fun `a path viewer is never picked by shebang`() {
        val viewers = FileViewers(listOf(ScriptPathStub()), fallback)
        val script = dir.resolve("run").apply { writeText("#!/usr/bin/env python3\nprint()\n") }

        assertSame(fallback, viewers.viewerFor(script))
        assertSame(fallback, viewers.viewerByName(Path.of("run"), "#!/usr/bin/env python3\n"))
    }

    @Test
    fun `a path viewer alone is not picked for other paths`() {
        val viewers = FileViewers(listOf(WorkflowStub()), fallback)
        val other = dir.resolve("a.yml").apply { writeText("a: 1") }

        assertSame(fallback, viewers.viewerFor(other))
        assertSame(fallback, viewers.viewerByName(Path.of("a.yml"), ""))
    }

    @Test
    fun `a diff by name is not shown as text at a path viewer's path, at other paths the code viewer is picked`() {
        val yaml = NamesViewer(extensions = setOf("yml"))
        val viewers = FileViewers(listOf(WorkflowStub(), yaml), fallback)

        assertSame(yaml, viewers.viewerByName(Path.of("config.yml"), ""))
        assertNull(viewers.openDiff(Path.of(".github/workflows/ci.yml"), "a: 1\n", TextDiff("", emptyList())))
    }

    @Test
    fun `isInFolders compares the innermost folders exactly`() {
        fun inFolders(
            path: String,
            vararg folders: String,
        ) = isInFolders(Path.of(path), *folders)

        assertTrue(inFolders(".github/workflows/ci.yml", ".github", "workflows"))
        assertTrue(inFolders("/repo/.github/workflows/ci.yml", ".github", "workflows"))
        assertTrue(inFolders("/repo/.github/workflows/ci.yml", "workflows"))
        assertTrue(inFolders("/repo/.github/workflows/ci.yml"))
        assertFalse(inFolders("workflows/ci.yml", ".github", "workflows"))
        assertFalse(inFolders(".github/workflows/sub/ci.yml", ".github", "workflows"))
        assertFalse(inFolders(".GitHub/Workflows/ci.yml", ".github", "workflows"))
        assertFalse(inFolders("ci.yml", ".github", "workflows"))
        assertFalse(inFolders("ci.yml", "workflows"))
        assertFalse(inFolders("/ci.yml", "workflows"))
    }
}
