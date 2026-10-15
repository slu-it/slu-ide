package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            JsonFileViewer() to listOf("package.json", "tsconfig.jsonc"),
            XmlFileViewer() to listOf("pom.xml", "schema.xsd", "style.xsl", "transform.xslt", "view.fxml"),
            SingleFileComponentFileViewer() to listOf("App.vue", "Page.svelte"),
            YamlFileViewer() to listOf("ci.yml", "config.yaml"),
            MdxFileViewer() to listOf("docs.mdx"),
            CssFileViewer() to listOf("site.css"),
            ScssFileViewer() to listOf("_vars.scss"),
            LessFileViewer() to listOf("theme.less"),
            SassFileViewer() to listOf("old.sass"),
        )

    private val others = listOf("notes.txt", "README", "py", "README.md", "index.html", "icon.svg")

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
    fun `code viewers open extensionless scripts by their shebang`() {
        val scripts: Map<CodeFileViewer?, List<String>> =
            mapOf(
                PythonFileViewer() to
                    listOf(
                        "#!/usr/bin/env python3\n",
                        "#!/usr/bin/python\n",
                        "#!/usr/bin/env -S python3 -u\n",
                        "#! /usr/bin/python3.12\r\nprint(1)\r\n",
                    ),
                JavaScriptFileViewer() to
                    listOf("#!/usr/bin/env node\n", "#!/usr/bin/env -S deno run\n", "#!/usr/bin/env bun\n"),
                null to listOf("#!/bin/bash\n", "#!/usr/bin/env ruby\n", "# python3\n"),
            )
        val viewers = listOf(PythonFileViewer(), JavaScriptFileViewer())

        for ((expected, shebangs) in scripts) {
            for ((index, shebang) in shebangs.withIndex()) {
                val script =
                    dir
                        .resolve(
                            "script-${expected?.javaClass?.simpleName}-$index",
                        ).apply { writeText(shebang) }
                val accepting = viewers.filter { it.canOpen(script) }.map { it::class }
                assertEquals(listOfNotNull(expected?.let { it::class }), accepting, shebang)
            }
        }
    }

    @Test
    fun `code viewers ignore the shebang of files with an extension or binary content`() {
        val python = PythonFileViewer()
        val javaScript = JavaScriptFileViewer()

        val nodeInPy = dir.resolve("tool.py").apply { writeText("#!/usr/bin/env node\n") }
        assertTrue(python.canOpen(nodeInPy))
        assertFalse(javaScript.canOpen(nodeInPy))
        assertFalse(python.canOpen(dir.resolve("notes.txt").apply { writeText("#!/usr/bin/env python3\n") }))
        val binary = dir.resolve("blob").apply { writeBytes("#!/usr/bin/env python3\n".toByteArray() + 0x00) }
        assertFalse(python.canOpen(binary))
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
}
