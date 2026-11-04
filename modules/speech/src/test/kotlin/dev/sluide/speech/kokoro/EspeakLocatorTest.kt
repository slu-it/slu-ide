package dev.sluide.speech.kokoro

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EspeakLocatorTest {
    @TempDir
    lateinit var dir: Path

    private fun file(
        relative: String,
        executable: Boolean,
    ): Path {
        val path = dir.resolve(relative)
        path.parent.createDirectories()
        path.writeText("#!/bin/sh\n")
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(if (executable) "rwxr-xr-x" else "rw-r--r--"),
        )
        return path
    }

    @Test
    fun `an executable configured path wins`() {
        val configured = file("conf/espeak-ng", executable = true)
        val locator = EspeakLocator({ configured.toString() }, { error("no lookup") }, emptyList())
        assertEquals(configured, locator.locate())
    }

    @Test
    fun `a configured path that is not executable falls through to the lookup`() {
        val configured = file("conf/espeak-ng", executable = false)
        val found = file("shell/espeak-ng", executable = true)
        val locator = EspeakLocator({ configured.toString() }, { found.toString() }, emptyList())
        assertEquals(found, locator.locate())
    }

    @Test
    fun `a configured folder or missing file falls through`() {
        val found = file("shell/espeak-ng", executable = true)
        assertEquals(found, EspeakLocator({ dir.toString() }, { found.toString() }, emptyList()).locate())
        assertEquals(
            found,
            EspeakLocator({ dir.resolve("nothing").toString() }, { found.toString() }, emptyList()).locate(),
        )
    }

    @Test
    fun `the first non blank line of the lookup is the path`() {
        val found = file("shell/espeak-ng", executable = true)
        val locator = EspeakLocator({ "" }, { "\n  \n  $found \nsecond line\n" }, emptyList())
        assertEquals(found, locator.locate())
    }

    @Test
    fun `a lookup result that is not an executable file falls through to the fallback folders`() {
        val notExecutable = file("shell/espeak-ng", executable = false)
        val fallback = file("fallback/espeak-ng", executable = true)
        val locator = EspeakLocator({ "" }, { notExecutable.toString() }, listOf(dir.resolve("empty"), fallback.parent))
        assertEquals(fallback, locator.locate())
    }

    @Test
    fun `a lookup that prints nothing or fails falls through`() {
        val fallback = file("fallback/espeak-ng", executable = true)
        assertEquals(fallback, EspeakLocator({ "" }, { null }, listOf(fallback.parent)).locate())
        assertEquals(fallback, EspeakLocator({ "" }, { "" }, listOf(fallback.parent)).locate())
    }

    @Test
    fun `nothing found is null`() {
        assertNull(EspeakLocator({ "" }, { null }, listOf(dir.resolve("none"))).locate())
    }

    @Test
    fun `the result is cached until invalidated`() {
        var lookups = 0
        val first = file("a/espeak-ng", executable = true)
        val second = file("b/espeak-ng", executable = true)
        var answer = first
        val locator =
            EspeakLocator({ "" }, {
                lookups++
                answer.toString()
            }, emptyList())
        assertEquals(first, locator.locate())
        answer = second
        assertEquals(first, locator.locate())
        assertEquals(1, lookups)
        locator.invalidate()
        assertEquals(second, locator.locate())
        assertEquals(2, lookups)
    }

    @Test
    fun `not found is cached too`() {
        var lookups = 0
        val locator =
            EspeakLocator({ "" }, {
                lookups++
                null
            }, emptyList())
        assertNull(locator.locate())
        assertNull(locator.locate())
        assertEquals(1, lookups)
    }
}
