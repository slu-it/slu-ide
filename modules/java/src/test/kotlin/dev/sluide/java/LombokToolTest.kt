package dev.sluide.java

import dev.sluide.localmodels.ArchiveFormat
import dev.sluide.localmodels.Checksum
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Lombok pin and the check an install runs on the jar. */
class LombokToolTest {
    @TempDir
    lateinit var dir: Path

    private val pin = LombokPin("1.18.48", URI("https://example.com/lombok-1.18.48.jar"), 10, "a".repeat(64))

    @Test
    fun `the pin is Lombok 1_18_48 from Maven Central by its size and sha256`() {
        val release = LombokTool.PIN.release()

        assertEquals("1.18.48", release.version)
        assertEquals(
            URI("https://repo1.maven.org/maven2/org/projectlombok/lombok/1.18.48/lombok-1.18.48.jar"),
            release.url,
        )
        assertEquals(2_055_065L, release.bytes)
        assertEquals(
            Checksum.Sha256("85477a4655ebb2c074a9099cfb749be454449fee564d4282610df1b85f7c508b"),
            release.checksum,
        )
        assertEquals(ArchiveFormat.PLAIN, release.format)
        assertEquals("lombok-1.18.48.jar", release.executable)
    }

    @Test
    fun `the smoke test accepts a regular file of the pinned size`() {
        val jar = dir.resolve("lombok-1.18.48.jar").also { it.writeBytes(ByteArray(10)) }

        assertNull(LombokTool.smokeTest(pin)(jar))
    }

    @Test
    fun `the smoke test refuses another size, a folder and a missing file`() {
        val short = dir.resolve("short.jar").also { it.writeBytes(ByteArray(9)) }
        val long = dir.resolve("long.jar").also { it.writeBytes(ByteArray(11)) }
        val folder = dir.resolve("folder.jar").createDirectories()

        val smoke = LombokTool.smokeTest(pin)

        assertEquals("the jar has 9 bytes, not the 10 of the pin", smoke(short))
        assertEquals("the jar has 11 bytes, not the 10 of the pin", smoke(long))
        assertEquals("the jar is not a regular file", smoke(folder))
        assertEquals("the jar is not a regular file", smoke(dir.resolve("missing.jar")))
        Files.delete(short)
    }

    @Test
    fun `the task row names Lombok and the percent`() {
        val release = pin.release()

        assertEquals("Installing Lombok 1.18.48", LombokTool.taskName(release, null))
        assertEquals("Downloading Lombok 1.18.48 35 %", LombokTool.taskName(release, 35))
    }
}
