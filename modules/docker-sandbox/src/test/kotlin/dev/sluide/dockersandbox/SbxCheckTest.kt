package dev.sluide.dockersandbox

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

/** `sbx` is looked up by a login shell whose `.profile`, in a test home folder, sets the `PATH`. */
class SbxCheckTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("home").createDirectories() }
    private val bin by lazy { root.resolve("bin").createDirectories() }
    private val environment by lazy {
        System.getenv() + mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh")
    }

    @BeforeEach
    fun onlyThePathOfTheProfile() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        home.resolve(".profile").writeText("PATH=\"$bin\"\nexport PATH\n")
    }

    @Test
    fun `sbx on the PATH of the login shell is installed`() {
        bin.resolve("sbx").apply {
            writeText("#!/bin/sh\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        assertTrue(SbxCheck.installed(environment))
    }

    @Test
    fun `sbx missing from the PATH of the login shell is not installed`() {
        assertFalse(SbxCheck.installed(environment))
    }

    @Test
    fun `a shell that cannot be started finds nothing`() {
        assertFalse(SbxCheck.installed(environment + ("SHELL" to root.resolve("no-such-shell").toString())))
    }
}
