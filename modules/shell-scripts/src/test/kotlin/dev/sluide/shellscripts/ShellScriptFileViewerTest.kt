package dev.sluide.shellscripts

import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.languages.ShellFileViewer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which files the script viewer takes from the shell viewer of startup files, whatever the order of the beans; the
 * same choice by name alone (diffs of a commit's file) is FileViewersTest's.
 */
class ShellScriptFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val shell = ShellFileViewer()
    private val scripts = ShellScriptFileViewer()
    private val fallback = DefaultTextFileViewer()

    private fun file(
        name: String,
        text: String = "echo hi\n",
    ) = dir.resolve(name).apply { writeText(text) }

    private val shebangs = listOf("#!/bin/bash", "#!/bin/sh -e", "#!/usr/bin/env zsh", "#!/bin/dash", "#!/bin/ksh93")

    @Test
    fun `scripts open in the script viewer and startup files in the shell viewer, in any order`() {
        for (order in listOf(listOf(shell, scripts), listOf(scripts, shell))) {
            val viewers = FileViewers(order, fallback)
            for (name in listOf("deploy.sh", "run.bash", "env.zsh", "old.ksh", "UPPER.SH")) {
                assertSame(scripts, viewers.viewerFor(file(name)), name)
            }
            for (shebang in shebangs) {
                assertSame(scripts, viewers.viewerFor(file("deploy", "$shebang\necho\n")), shebang)
            }
            for (name in listOf(".bashrc", ".bash_profile", ".zshrc", ".zprofile", ".profile")) {
                assertSame(shell, viewers.viewerFor(file(name, "#!/bin/bash\n")), name)
            }
            assertSame(fallback, viewers.viewerFor(file("deploy", "#!/usr/bin/env fish\n")))
            assertSame(fallback, viewers.viewerFor(file("deploy.sh.bak")))
        }
    }

    @Test
    fun `only readable text files with a script extension are opened by name, and diffs are highlighted as shell`() {
        val binary = dir.resolve("blob.sh").apply { writeBytes(byteArrayOf(0)) }
        assertNull(FileViewers(listOf(scripts), fallback).viewerFor(binary))
        assertFalse(scripts.canOpen(file("deploy", "#!/bin/bash\n")), "shebangs are FileViewers' to read")
        assertTrue(FileViewers(listOf(scripts), fallback).showsDiffs(scripts))
        assertSame(Highlighters.shell, scripts.sourceHighlighter)
    }
}
