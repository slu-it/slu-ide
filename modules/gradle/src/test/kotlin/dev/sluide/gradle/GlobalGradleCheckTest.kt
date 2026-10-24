package dev.sluide.gradle

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** `gradle` is looked up by a login shell whose `.profile`, in a test home folder, sets the `PATH`. */
class GlobalGradleCheckTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("home").createDirectories() }
    private val bin by lazy { root.resolve("bin").createDirectories() }
    private val environment by lazy { System.getenv() + mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh") }

    @BeforeEach
    fun onlyThePathOfTheProfile() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        home.resolve(".profile").writeText("PATH=\"$bin\"\nexport PATH\n")
    }

    private fun stubGradle() {
        bin.resolve("gradle").apply {
            writeText("#!/bin/sh\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `gradle on the PATH of the login shell is available`() {
        stubGradle()
        assertTrue(GlobalGradleCheck.available(environment))
    }

    @Test
    fun `gradle missing from the PATH of the login shell is not available`() {
        assertFalse(GlobalGradleCheck.available(environment))
    }

    @Test
    fun `a shell that cannot be started finds nothing`() {
        stubGradle()
        assertFalse(GlobalGradleCheck.available(environment + ("SHELL" to root.resolve("no-such-shell").toString())))
    }

    @Test
    fun `a login profile that hangs is stopped at the timeout`() {
        stubGradle()
        home.resolve(".profile").writeText("PATH=\"$bin\"\nexport PATH\n/bin/sleep 30\n")
        val started = System.nanoTime()
        assertFalse(GlobalGradleCheck.available(environment, 1.seconds))
        assertTrue(System.nanoTime() - started < 10_000_000_000, "returns soon after the timeout")
    }
}
