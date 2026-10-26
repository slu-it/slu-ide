package dev.sluide.fileviewers

import dev.sluide.fileviewers.bicep.BicepParamFileViewer
import dev.sluide.fileviewers.code.CodeFileViewer
import dev.sluide.fileviewers.languages.AssemblerFileViewer
import dev.sluide.fileviewers.languages.BatchFileViewer
import dev.sluide.fileviewers.languages.CFileViewer
import dev.sluide.fileviewers.languages.CSharpFileViewer
import dev.sluide.fileviewers.languages.CaddyfileFileViewer
import dev.sluide.fileviewers.languages.ClojureFileViewer
import dev.sluide.fileviewers.languages.CodeownersFileViewer
import dev.sluide.fileviewers.languages.ConfigFileViewer
import dev.sluide.fileviewers.languages.CppFileViewer
import dev.sluide.fileviewers.languages.CssFileViewer
import dev.sluide.fileviewers.languages.DFileViewer
import dev.sluide.fileviewers.languages.DartFileViewer
import dev.sluide.fileviewers.languages.DockerfileFileViewer
import dev.sluide.fileviewers.languages.FortranFileViewer
import dev.sluide.fileviewers.languages.GitBlameIgnoreRevsFileViewer
import dev.sluide.fileviewers.languages.GitattributesFileViewer
import dev.sluide.fileviewers.languages.GoFileViewer
import dev.sluide.fileviewers.languages.GoModFileViewer
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
import dev.sluide.fileviewers.languages.LispFileViewer
import dev.sluide.fileviewers.languages.LuaFileViewer
import dev.sluide.fileviewers.languages.MailmapFileViewer
import dev.sluide.fileviewers.languages.MakefileFileViewer
import dev.sluide.fileviewers.languages.MdxFileViewer
import dev.sluide.fileviewers.languages.PascalFileViewer
import dev.sluide.fileviewers.languages.PerlFileViewer
import dev.sluide.fileviewers.languages.PhpFileViewer
import dev.sluide.fileviewers.languages.PowerShellFileViewer
import dev.sluide.fileviewers.languages.PropertiesFileViewer
import dev.sluide.fileviewers.languages.PythonFileViewer
import dev.sluide.fileviewers.languages.RubyFileViewer
import dev.sluide.fileviewers.languages.RustFileViewer
import dev.sluide.fileviewers.languages.SassFileViewer
import dev.sluide.fileviewers.languages.ScalaFileViewer
import dev.sluide.fileviewers.languages.ScssFileViewer
import dev.sluide.fileviewers.languages.ShellFileViewer
import dev.sluide.fileviewers.languages.SingleFileComponentFileViewer
import dev.sluide.fileviewers.languages.SqlFileViewer
import dev.sluide.fileviewers.languages.SshConfigFileViewer
import dev.sluide.fileviewers.languages.TclFileViewer
import dev.sluide.fileviewers.languages.TerraformFileViewer
import dev.sluide.fileviewers.languages.TomlFileViewer
import dev.sluide.fileviewers.languages.TsxFileViewer
import dev.sluide.fileviewers.languages.TypeScriptFileViewer
import dev.sluide.fileviewers.languages.VhdlFileViewer
import dev.sluide.fileviewers.languages.VisualBasicFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Which files each code viewer accepts. Add a row per viewer. */
class CodeFileViewersTest {
    @TempDir
    lateinit var dir: Path

    private val samples: Map<CodeFileViewer, List<String>> =
        mapOf(
            PythonFileViewer() to listOf("main.py", "gui.pyw", "types.pyi", "LEGACY.PY"),
            RubyFileViewer() to
                listOf(
                    "app.rb",
                    "tasks.rake",
                    "lib.gemspec",
                    "config.ru",
                    "Gemfile",
                    "Rakefile",
                    "Vagrantfile",
                    "Podfile",
                    "Brewfile",
                    "Fastfile",
                    "Guardfile",
                ),
            PhpFileViewer() to listOf("index.php", "view.phtml"),
            PerlFileViewer() to listOf("script.pl", "Module.pm"),
            LuaFileViewer() to listOf("init.lua"),
            ClojureFileViewer() to listOf("core.clj", "app.cljs", "shared.cljc", "data.edn"),
            LispFileViewer() to listOf("a.lisp", "b.lsp", "init.el", "d.scm", "e.ss", "main.rkt"),
            TclFileViewer() to listOf("build.tcl"),
            BatchFileViewer() to listOf("run.bat", "setup.cmd", "gradlew.bat"),
            KotlinFileViewer() to listOf("Main.kt", "build.gradle.kts"),
            GroovyFileViewer() to
                listOf("build.gradle", "settings.gradle", "Script.groovy", "Jenkinsfile", "jenkinsfile.prod"),
            JavaFileViewer() to listOf("Main.java"),
            CFileViewer() to listOf("main.c", "OLD.C"),
            CppFileViewer() to
                listOf("main.cpp", "a.cc", "b.cxx", "c.c++", "util.h", "v.hpp", "v.hh", "v.hxx", "blink.ino"),
            CSharpFileViewer() to listOf("Program.cs", "script.csx", "build.cake"),
            GoFileViewer() to listOf("main.go"),
            GoModFileViewer() to listOf("go.mod", "go.work", "GO.MOD"),
            RustFileViewer() to listOf("lib.rs"),
            DFileViewer() to listOf("app.d"),
            ScalaFileViewer() to listOf("Main.scala", "build.sc"),
            DartFileViewer() to listOf("main.dart"),
            VisualBasicFileViewer() to listOf("Form.vb", "run.vbs"),
            PascalFileViewer() to listOf("unit.pas", "app.dpr", "app.lpr"),
            FortranFileViewer() to listOf("a.f", "b.for", "c.f77", "d.f90", "e.f95", "f.f03", "g.f08"),
            AssemblerFileViewer() to listOf("boot.asm", "start.s", "Start.S"),
            VhdlFileViewer() to listOf("alu.vhd", "alu.vhdl"),
            JavaScriptFileViewer() to listOf("app.js", "module.mjs", "config.cjs"),
            TypeScriptFileViewer() to listOf("app.ts", "types.d.ts", "module.mts", "config.cts"),
            JsxFileViewer() to listOf("Button.jsx"),
            TsxFileViewer() to listOf("App.tsx"),
            SingleFileComponentFileViewer() to listOf("App.vue", "Page.svelte"),
            MdxFileViewer() to listOf("docs.mdx"),
            CssFileViewer() to listOf("site.css"),
            ScssFileViewer() to listOf("_vars.scss"),
            LessFileViewer() to listOf("theme.less"),
            SassFileViewer() to listOf("old.sass"),
            SqlFileViewer() to listOf("schema.sql"),
            // Scripts to run (.sh and the like) are the shell-scripts module's.
            ShellFileViewer() to listOf(".bashrc", ".bash_profile", ".zshrc", ".profile"),
            PowerShellFileViewer() to listOf("build.ps1", "Tools.psm1", "Tools.psd1"),
            DockerfileFileViewer() to
                listOf("Dockerfile", "Containerfile", "Dockerfile.dev", "dockerfile.prod.local", "app.Dockerfile"),
            MakefileFileViewer() to listOf("Makefile", "GNUmakefile", "rules.mk"),
            IniFileViewer() to listOf("php.ini"),
            ConfigFileViewer() to
                listOf("setup.cfg", "nginx.conf", ".npmrc", ".editorconfig", ".gitconfig", ".gitmodules"),
            CodeownersFileViewer() to listOf("CODEOWNERS", "codeowners"),
            GitattributesFileViewer() to listOf(".gitattributes", "foo.gitattributes"),
            MailmapFileViewer() to listOf(".mailmap"),
            Json5FileViewer() to listOf("tsconfig.json5", "RULES.JSON5"),
            JsonLinesFileViewer() to listOf("events.jsonl", "log.ndjson"),
            PropertiesFileViewer() to
                listOf("application.properties", "gradle.properties", "gradle-wrapper.properties"),
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
            TerraformFileViewer() to
                listOf(
                    "main.tf",
                    "prod.tfvars",
                    "Main.TF",
                    "config.hcl",
                    "web.pkr.hcl",
                    ".terraform.lock.hcl",
                    "job.nomad",
                ),
            KqlFileViewer() to listOf("failures.kql", "Commands.csl", "OLD.KQL"),
        )

    private val others =
        listOf(
            "notes.txt",
            "README",
            "py",
            "README.md",
            "index.html",
            "icon.svg",
            "yarn.lock",
            "main.bicep",
            "main.tf.json",
            "prod.tfvars.json",
            "build.sh",
            "run.bash",
            "env.zsh",
            "old.ksh",
            "main.go.txt",
            "site.pp",
            "go.sum",
            "app.dartx",
            "lib.rlib",
            "kernel.cl",
            "Gemfile.lock",
        )

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
        val python = PythonFileViewer()
        val javaScript = JavaScriptFileViewer()
        val shell = ShellFileViewer()
        val powerShell = PowerShellFileViewer()
        val scala = ScalaFileViewer()
        val ruby = RubyFileViewer()
        val php = PhpFileViewer()
        val perl = PerlFileViewer()
        val lua = LuaFileViewer()
        val tcl = TclFileViewer()
        val fallback = DefaultTextFileViewer()
        val scriptViewers = listOf(python, javaScript, shell, powerShell, scala, ruby, php, perl, lua, tcl)
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
                powerShell to listOf("#!/usr/bin/env pwsh\n"),
                scala to listOf("#!/usr/bin/env scala\n", "#!/usr/bin/env -S scala-cli shebang\n"),
                ruby to listOf("#!/usr/bin/env ruby\n", "#!/usr/bin/ruby3.2\n"),
                php to listOf("#!/usr/bin/env php\n", "#!/usr/bin/php8.3 -q\n"),
                perl to listOf("#!/usr/bin/perl -w\n", "#!/usr/bin/env perl\n"),
                lua to listOf("#!/usr/bin/env lua\n", "#!/usr/bin/lua5.4\n"),
                tcl to listOf("#!/usr/bin/env tclsh\n", "#!/usr/bin/wish\n"),
                // Shell scripts are the shell-scripts module's; the startup files' viewer takes no shebang.
                fallback to listOf("# ruby\n", "# python3\n", "#!/usr/bin/env fish\n", "#!/bin/bash\n"),
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
        val viewers = FileViewers(listOf(fallback) + samples.keys, fallback)

        // Pipfile.lock is JSON: see ViewerSelectionTest.
        for (name in listOf("Pipfile", "uv.lock", "poetry.lock", "pdm.lock", "Cargo.lock")) {
            assertSame(toml, viewers.viewerFor(file(name)), name)
        }
        assertSame(fallback, viewers.viewerFor(file("yarn.lock")))
    }
}
