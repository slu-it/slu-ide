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
        for (name in accepted) assertTrue(match.matches(file(name)), name)
        for (name in rejected) assertFalse(match.matches(file(name)), name)
    }

    @Test
    fun `names and patterns match only readable text files`() {
        val match = FileMatch(emptySet(), names = setOf("makefile"), namePatterns = listOf(Regex("dockerfile.*")))
        assertFalse(match.matches(dir.resolve("Makefile").apply { writeBytes(byteArrayOf(0x61, 0x00)) }))
        assertFalse(match.matches(dir.resolve("Dockerfile")))
    }

    @Test
    fun `matches extensionless files by the interpreter of their shebang`() {
        val match = FileMatch(emptySet(), interpreters = setOf("python", "python3"))

        assertTrue(match.matches(file("manage", "#!/usr/bin/python3\nprint(1)\n")))
        assertTrue(match.matches(file("tool", "#!/usr/bin/env python\n")))
        assertTrue(match.matches(file("unbuffered", "#!/usr/bin/env -S python3 -u\n")))
        assertTrue(match.matches(file("spaced", "#! /usr/local/bin/python3\n")))
        assertTrue(match.matches(file("windows", "#!/usr/bin/env python3\r\nprint(1)\r\n")))
        assertTrue(match.matches(file("versioned", "#!/usr/bin/python3.12\n")))
        assertFalse(match.matches(file("shell", "#!/bin/bash\n")))
        assertFalse(match.matches(file("comment", "# python3\n")))
        assertFalse(match.matches(file("later", "\n#!/usr/bin/env python3\n")))
    }

    @Test
    fun `ignores the shebang of files with an extension and of binary files`() {
        val match = FileMatch(setOf("py"), interpreters = setOf("python3"))

        assertFalse(match.matches(file("run.txt", "#!/usr/bin/env python3\n")))
        assertTrue(match.matches(file("run.py", "#!/usr/bin/env node\n")))
        val binary = dir.resolve("blob").apply { writeBytes("#!/usr/bin/env python3\n".toByteArray() + 0x00) }
        assertFalse(match.matches(binary))
        assertFalse(match.matches(dir.resolve("missing")))
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
}
