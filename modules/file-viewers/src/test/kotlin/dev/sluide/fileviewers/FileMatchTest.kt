package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMatchTest {
    @TempDir
    lateinit var dir: Path

    private fun file(
        name: String,
        text: String = "x\n",
    ) = dir.resolve(name).apply { writeText(text) }

    @Test
    fun `matches extensions, exact names and whole-name patterns case-insensitively`() {
        val match =
            FileMatch(
                extensions = setOf("mk"),
                names = setOf("Makefile", ".bashrc"),
                namePatterns = listOf(Regex("""dockerfile\..*"""), Regex("""\.env(\..*)?""")),
            )

        val accepted = listOf("rules.MK", "makefile", ".bashrc", "Dockerfile.dev", ".env", ".env.local")
        val rejected = listOf("Makefile.bak", "my.bashrc", "Dockerfile", "app.dockerfile", "x.env", ".envrc")
        for (name in accepted) assertTrue(match.matchesName(file(name)), name)
        for (name in rejected) assertFalse(match.matchesName(file(name)), name)
    }

    @Test
    fun `names and patterns match only readable text files`() {
        val match = FileMatch(emptySet(), names = setOf("makefile"), namePatterns = listOf(Regex("dockerfile.*")))
        assertFalse(match.matchesName(dir.resolve("Makefile").apply { writeBytes(byteArrayOf(0x61, 0x00)) }))
        assertFalse(match.matchesName(dir.resolve("Dockerfile")))
    }

    @Test
    fun `matches interpreters with and without their version`() {
        val match = FileMatch(emptySet(), interpreters = setOf("python", "python3"))

        for (interpreter in listOf("python", "python3", "Python3", "python3.12", "python2", "python2.7")) {
            assertTrue(match.matchesInterpreter(interpreter), interpreter)
        }
        for (interpreter in listOf("bash", "pythonw", "ipython", "py")) {
            assertFalse(match.matchesInterpreter(interpreter), interpreter)
        }
    }

    @Test
    fun `name matches do not look at the shebang`() {
        val match = FileMatch(setOf("py"), interpreters = setOf("python3"))

        assertFalse(match.matchesName(file("tool", "#!/usr/bin/env python3\n")))
        assertTrue(match.matchesName(file("run.py", "#!/usr/bin/env node\n")))
    }

    @Test
    fun `finds the interpreter a shebang names`() {
        assertEquals("bash", shebangInterpreter("#!/bin/bash"))
        assertEquals("sh", shebangInterpreter("#! /bin/sh -e"))
        assertEquals("bash", shebangInterpreter("#!/usr/bin/env bash"))
        assertEquals("python3", shebangInterpreter("#!/usr/bin/env -S python3 -u"))
        assertEquals("node", shebangInterpreter("#!/usr/bin/env -S NODE_ENV=production node"))
        assertEquals("python3.12", shebangInterpreter("#!/usr/bin/env /opt/bin/python3.12"))
        assertNull(shebangInterpreter("#!env"))
        assertNull(shebangInterpreter("#!/usr/bin/env"))
        assertNull(shebangInterpreter("#!"))
        assertNull(shebangInterpreter("#!/"))
        assertNull(shebangInterpreter("# !/bin/bash"))
        assertNull(shebangInterpreter("print(1)"))
    }

    @Test
    fun `skips the arguments of env options that take one`() {
        assertEquals("python3", shebangInterpreter("#!/usr/bin/env -u PYTHONPATH python3"))
        assertEquals("python3", shebangInterpreter("#!/usr/bin/env --unset PYTHONPATH python3"))
        assertEquals("node", shebangInterpreter("#!/usr/bin/env -C /srv/app node"))
        assertEquals("node", shebangInterpreter("#!/usr/bin/env --chdir /srv/app node"))
        assertEquals("bash", shebangInterpreter("#!/usr/bin/env -P /usr/local/bin:/bin bash"))
        assertEquals("python3", shebangInterpreter("#!/usr/bin/env -i --unset=HOME -uLANG python3"))
        assertEquals("bash", shebangInterpreter("#!/usr/bin/ENV bash"))
        assertNull(shebangInterpreter("#!/usr/bin/env -u PYTHONPATH"))
    }

    @Test
    fun `takes the uv run launcher for python`() {
        assertEquals("python", shebangInterpreter("#!/usr/bin/env -S uv run --script"))
        assertEquals("python", shebangInterpreter("#!/usr/bin/env -S uv --quiet run --with rich --script"))
        assertEquals("python", shebangInterpreter("#!/home/me/.local/bin/uv run"))
        assertEquals("uv", shebangInterpreter("#!/usr/bin/env -S uv tool run ruff"))
        assertEquals("uv", shebangInterpreter("#!/usr/bin/env uv"))
    }
}
