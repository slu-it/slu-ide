package dev.sluide.fileviewers

import dev.sluide.fileviewers.bicep.BicepParamFileViewer
import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.html.HtmlFileViewer
import dev.sluide.fileviewers.image.SvgFileViewer
import dev.sluide.fileviewers.json.JsonFileViewer
import dev.sluide.fileviewers.languages.CaddyfileFileViewer
import dev.sluide.fileviewers.languages.ConfigFileViewer
import dev.sluide.fileviewers.languages.CssFileViewer
import dev.sluide.fileviewers.languages.DockerfileFileViewer
import dev.sluide.fileviewers.languages.GitBlameIgnoreRevsFileViewer
import dev.sluide.fileviewers.languages.IgnoreFileViewer
import dev.sluide.fileviewers.languages.IniFileViewer
import dev.sluide.fileviewers.languages.JavaFileViewer
import dev.sluide.fileviewers.languages.JavaScriptFileViewer
import dev.sluide.fileviewers.languages.JsxFileViewer
import dev.sluide.fileviewers.languages.KotlinFileViewer
import dev.sluide.fileviewers.languages.KqlFileViewer
import dev.sluide.fileviewers.languages.LessFileViewer
import dev.sluide.fileviewers.languages.MakefileFileViewer
import dev.sluide.fileviewers.languages.MdxFileViewer
import dev.sluide.fileviewers.languages.PowerShellFileViewer
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
import dev.sluide.fileviewers.languages.YamlFileViewer
import dev.sluide.fileviewers.notebook.NotebookFileViewer
import dev.sluide.fileviewers.xml.XmlFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which files each code viewer accepts. Add a row per viewer. */
class CodeFileViewersTest {
    @TempDir
    lateinit var dir: Path

    private val samples: Map<CodeFileViewer, List<String>> =
        mapOf(
            PythonFileViewer() to listOf("main.py", "gui.pyw", "types.pyi", "LEGACY.PY"),
            KotlinFileViewer() to listOf("Main.kt", "build.gradle.kts"),
            JavaFileViewer() to listOf("Main.java"),
            JavaScriptFileViewer() to listOf("app.js", "module.mjs", "config.cjs"),
            TypeScriptFileViewer() to listOf("app.ts", "types.d.ts", "module.mts", "config.cts"),
            JsxFileViewer() to listOf("Button.jsx"),
            TsxFileViewer() to listOf("App.tsx"),
            SingleFileComponentFileViewer() to listOf("App.vue", "Page.svelte"),
            YamlFileViewer() to listOf("ci.yml", "config.yaml"),
            MdxFileViewer() to listOf("docs.mdx"),
            CssFileViewer() to listOf("site.css"),
            ScssFileViewer() to listOf("_vars.scss"),
            LessFileViewer() to listOf("theme.less"),
            SassFileViewer() to listOf("old.sass"),
            SqlFileViewer() to listOf("schema.sql"),
            ShellFileViewer() to
                listOf("build.sh", "run.bash", "env.zsh", "old.ksh", ".bashrc", ".bash_profile", ".zshrc", ".profile"),
            PowerShellFileViewer() to listOf("build.ps1", "Tools.psm1", "Tools.psd1"),
            DockerfileFileViewer() to
                listOf("Dockerfile", "Containerfile", "Dockerfile.dev", "dockerfile.prod.local", "app.Dockerfile"),
            MakefileFileViewer() to listOf("Makefile", "GNUmakefile", "rules.mk"),
            IniFileViewer() to listOf("php.ini"),
            ConfigFileViewer() to listOf("setup.cfg", "nginx.conf", ".npmrc", ".editorconfig", ".gitconfig"),
            IgnoreFileViewer() to
                listOf(
                    ".gitignore",
                    "Python.gitignore",
                    ".dockerignore",
                    "Dockerfile.dockerignore",
                    "Dockerfile.dev.dockerignore",
                    ".npmignore",
                    ".prettierignore",
                    ".eslintignore",
                    ".helmignore",
                ),
            GitBlameIgnoreRevsFileViewer() to listOf(".git-blame-ignore-revs"),
            SshConfigFileViewer() to listOf("sshd_config", "ssh_config"),
            CaddyfileFileViewer() to listOf("Caddyfile", "Caddyfile.dev", "site.caddyfile"),
            TomlFileViewer() to
                listOf("pyproject.toml", "Cargo.toml", "uv.lock", "poetry.lock", "pdm.lock", "Cargo.lock", "Pipfile"),
            BicepParamFileViewer() to listOf("main.bicepparam", "Prod.BICEPPARAM"),
            KqlFileViewer() to listOf("failures.kql", "Commands.csl", "OLD.KQL"),
        )

    private val others =
        listOf("notes.txt", "README", "py", "README.md", "index.html", "icon.svg", "yarn.lock", "main.bicep")

    private fun file(name: String) = dir.resolve(name).apply { writeText("x = 1\n") }

    @Test
    fun `every code viewer opens exactly its own text files`() {
        val all = samples.values.flatten() + others
        for ((viewer, own) in samples) {
            val accepted = all.filter { viewer.canOpen(file(it)) }
            assertEquals(own, accepted, viewer::class.simpleName)
        }
    }

    @Test
    fun `the JSON viewer opens its text files by extension and Pipfile lock by name`() {
        val json = JsonFileViewer()
        val all = listOf("package.json", "tsconfig.jsonc", "Pipfile.lock", "pipfile.LOCK") + others

        assertEquals(
            listOf("package.json", "tsconfig.jsonc", "Pipfile.lock", "pipfile.LOCK"),
            all.filter { json.canOpen(file(it)) },
        )
        assertFalse(json.canOpen(dir.resolve("missing.json")))
        assertFalse(json.canOpen(dir.resolve("binary.json").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }))
        assertFalse(json.canOpen(dir.resolve("Pipfile.lock-binary").apply { writeBytes(byteArrayOf(0x61, 0)) }))
    }

    @Test
    fun `the XML viewer opens its text files by extension, and svg html and notebooks stay with theirs`() {
        val xml = XmlFileViewer()
        val own = listOf("pom.xml", "schema.xsd", "style.xsl", "transform.xslt", "view.fxml", "app.iml", "BUILD.XML")

        assertEquals(own, (own + others).filter { xml.canOpen(file(it)) })
        assertFalse(xml.canOpen(dir.resolve("missing.xml")))
        assertFalse(xml.canOpen(dir.resolve("binary.xml").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }))

        val fallback = DefaultTextFileViewer()
        val svg = SvgFileViewer()
        val html = HtmlFileViewer()
        val notebook = NotebookFileViewer()
        val viewers = FileViewers(listOf(fallback, xml, svg, html, notebook), fallback)
        assertSame(xml, viewers.viewerFor(file("pom.xml")))
        assertSame(svg, viewers.viewerFor(file("icon.svg")))
        assertSame(html, viewers.viewerFor(file("index.html")))
        assertSame(notebook, viewers.viewerFor(file("analysis.ipynb")))
    }

    @Test
    fun `code viewers open extensionless scripts by their shebang`() {
        val python = PythonFileViewer()
        val javaScript = JavaScriptFileViewer()
        val shell = ShellFileViewer()
        val powerShell = PowerShellFileViewer()
        val fallback = DefaultTextFileViewer()
        val scriptViewers = listOf(python, javaScript, shell, powerShell)
        val viewers = FileViewers(scriptViewers, fallback)
        val scripts: Map<FileViewer, List<String>> =
            mapOf(
                python to
                    listOf(
                        "#!/usr/bin/env python3\n",
                        "#!/usr/bin/python\n",
                        "#!/usr/bin/env -S python3 -u\n",
                        "#! /usr/bin/python3.12\r\nprint(1)\r\n",
                        "#!/usr/bin/env -S uv run --script\n",
                    ),
                javaScript to
                    listOf("#!/usr/bin/env node\n", "#!/usr/bin/env -S deno run\n", "#!/usr/bin/env bun\n"),
                shell to
                    listOf("#!/bin/bash\n", "#!/bin/sh -e\n", "#!/usr/bin/env zsh\n", "#!/bin/dash\n", "#!/bin/ksh\n"),
                powerShell to listOf("#!/usr/bin/env pwsh\n"),
                fallback to listOf("#!/usr/bin/env ruby\n", "# python3\n", "#!/usr/bin/env fish\n"),
            )

        for ((expected, shebangs) in scripts) {
            for ((index, shebang) in shebangs.withIndex()) {
                val script = dir.resolve("script-${expected.javaClass.simpleName}-$index").apply { writeText(shebang) }
                assertSame(expected, viewers.viewerFor(script), shebang)
                assertFalse(scriptViewers.any { it.canOpen(script) }, "canOpen $shebang")
            }
        }
    }

    @Test
    fun `code viewers ignore the shebang of files with an extension or binary content`() {
        val python = PythonFileViewer()
        val javaScript = JavaScriptFileViewer()
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(javaScript, python), fallback)

        val nodeInPy = dir.resolve("tool.py").apply { writeText("#!/usr/bin/env node\n") }
        assertSame(python, viewers.viewerFor(nodeInPy))
        val pythonInTxt = dir.resolve("notes.txt").apply { writeText("#!/usr/bin/env python3\n") }
        assertSame(fallback, viewers.viewerFor(pythonInTxt))
        val binary = dir.resolve("blob").apply { writeBytes("#!/usr/bin/env python3\n".toByteArray() + 0x00) }
        assertNull(viewers.viewerFor(binary))
    }

    @Test
    fun `only Makefiles are indented with tabs by default`() {
        for (viewer in samples.keys) {
            val tabs = viewer is MakefileFileViewer
            assertEquals(tabs, viewer.defaultIndent.useTabs, viewer::class.simpleName)
        }
    }

    @Test
    fun `code viewers do not open binary or missing files`() {
        for ((viewer, own) in samples) {
            val name = own.first()
            assertFalse(viewer.canOpen(dir.resolve("missing-$name")), "missing $name")
            val binary = dir.resolve("binary-$name").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }
            assertFalse(viewer.canOpen(binary), "binary $name")
        }
    }

    @Test
    fun `code viewers are chosen before the default text viewer`() {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback) + samples.keys, fallback)

        for ((viewer, own) in samples) {
            assertSame(viewer, viewers.viewerFor(file(own.first())))
        }
        assertSame(fallback, viewers.viewerFor(file("notes.txt")))
    }

    @Test
    fun `lock files open in the viewer of their format`() {
        val fallback = DefaultTextFileViewer()
        val toml = samples.keys.single { it is TomlFileViewer }
        val json = JsonFileViewer()
        val viewers = FileViewers(listOf(fallback, json) + samples.keys, fallback)

        assertSame(json, viewers.viewerFor(file("Pipfile.lock")), "Pipfile.lock is JSON")
        for (name in listOf("Pipfile", "uv.lock", "poetry.lock", "pdm.lock", "Cargo.lock")) {
            assertSame(toml, viewers.viewerFor(file(name)), name)
        }
        assertSame(fallback, viewers.viewerFor(file("yarn.lock")))
    }
}
