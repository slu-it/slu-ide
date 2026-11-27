package dev.sluide.git.ssh

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AskpassFilesTest {
    @TempDir
    lateinit var folder: Path

    private val resources =
        mapOf("askpass.jar" to "helper".toByteArray(), "kotlin-stdlib.jar" to "stdlib".toByteArray())

    private fun install(
        java: Path = Path.of("/opt/thoreau/runtime/bin/java"),
        files: Map<String, ByteArray> = resources,
        linux: Boolean = true,
    ) = AskpassFiles.install(folder.resolve("askpass"), java, linux) { name -> files[name]?.inputStream() }

    @ParameterizedTest
    @CsvSource("true, 'LC_ALL=C.UTF-8 '", "false, ''")
    fun `the wrapper execs java with the class path and passes ssh's arguments on, every value quoted`(
        linux: Boolean,
        locale: String,
    ) {
        val script =
            AskpassFiles.wrapperScript(
                Path.of("/Apps/It's Thoreau/bin/java"),
                listOf(Path.of("/h/o'k/askpass.jar"), Path.of("/h/o'k/kotlin-stdlib.jar")),
                linux,
            )

        assertEquals(
            "#!/bin/sh\n" +
                "unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS JDK_JAVA_OPTIONS\n" +
                "${locale}exec '/Apps/It'\\''s Thoreau/bin/java' -cp " +
                "'/h/o'\\''k/askpass.jar:/h/o'\\''k/kotlin-stdlib.jar' dev.sluide.git.askpass.Askpass \"\$@\"\n",
            script,
        )
    }

    @Test
    fun `the installed wrapper execs java with whole arguments, the UTF-8 locale and without JVM options`() {
        // A stand-in java at a path with a quote, a space and a dollar sign: prints its pid, locale and arguments.
        val bin = folder.resolve("it's \$HOME bin").createDirectories()
        val java = bin.resolve("java")
        java.writeText(
            "#!/bin/sh\necho \$\$\necho \"\$LC_ALL\"\n" +
                // The JVM would print "Picked up …" for each of these into ssh's, and so git's, error output.
                "echo \"\${JAVA_TOOL_OPTIONS-none}|\${_JAVA_OPTIONS-none}|\${JDK_JAVA_OPTIONS-none}\"\n" +
                "for a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\n",
        )
        java.toFile().setExecutable(true)
        val script = install(java)

        val process =
            ProcessBuilder(script.toString(), "Enter passphrase for key 'my key': ")
                .apply {
                    environment()["JAVA_TOOL_OPTIONS"] = "-Dhttp.proxyHost=proxy"
                    environment()["_JAVA_OPTIONS"] = "-Xmx1m"
                    environment()["JDK_JAVA_OPTIONS"] = "-Dx=y"
                }.start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(process.pid().toString(), lines.first(), "exec: no shell stays between ssh and the helper")
        val folderOfJars = script.parent
        assertEquals(
            listOf(
                "C.UTF-8",
                "none|none|none",
                "[-cp]",
                "[${folderOfJars.resolve("askpass.jar")}:${folderOfJars.resolve("kotlin-stdlib.jar")}]",
                "[dev.sluide.git.askpass.Askpass]",
                "[Enter passphrase for key 'my key': ]",
            ),
            lines.drop(1),
        )
    }

    @Test
    fun `installs the jars and an owner-only wrapper into a folder named by their content and the java path`() {
        val script = install()

        assertEquals(AskpassFiles.SCRIPT, script.fileName.toString())
        assertEquals(folder.resolve("askpass"), script.parent.parent)
        assertEquals(
            16,
            script.parent.fileName
                .toString()
                .length,
        )
        assertContentEquals("helper".toByteArray(), script.resolveSibling("askpass.jar").readBytes())
        assertContentEquals("stdlib".toByteArray(), script.resolveSibling("kotlin-stdlib.jar").readBytes())
        // The jars are data, only the script is a program.
        assertFalse(Files.isExecutable(script.resolveSibling("askpass.jar")))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
        assertEquals(
            AskpassFiles.wrapperScript(
                Path.of("/opt/thoreau/runtime/bin/java"),
                listOf(script.resolveSibling("askpass.jar"), script.resolveSibling("kotlin-stdlib.jar")),
                linux = true,
            ),
            script.readText(),
        )
        assertEquals(setOf("askpass", "askpass.jar", "kotlin-stdlib.jar"), names(script.parent), "no temporary files")
    }

    @Test
    fun `the same files install into the same folder, other jars or another java into another`() {
        val first = install()
        assertEquals(first, install())

        assertNotEquals(first.parent, install(java = Path.of("/other/bin/java")).parent)
        assertNotEquals(first.parent, install(files = resources + ("askpass.jar" to "helpe2".toByteArray())).parent)
        // The same bytes split differently between the jars are other content.
        val shifted = mapOf("askpass.jar" to "helpers".toByteArray(), "kotlin-stdlib.jar" to "tdlib".toByteArray())
        assertNotEquals(first.parent, install(files = shifted).parent)
    }

    @Test
    fun `a damaged file is written again`() {
        val script = install()
        script.resolveSibling("askpass.jar").writeText("broken")
        script.writeText("#!/bin/sh\nexit 0\n")

        install()

        assertContentEquals("helper".toByteArray(), script.resolveSibling("askpass.jar").readBytes())
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)))
        assertContains(script.readText(), " dev.sluide.git.askpass.Askpass ")

        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rw-r--r--"))
        install()
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(script)), "made private")
    }

    @Test
    fun `a missing resource fails the install`() {
        assertFailsWith<IOException> { install(files = resources - "kotlin-stdlib.jar") }
    }

    @Test
    fun `whole files are left alone`() {
        val script = install()
        val files = listOf(script, script.resolveSibling("askpass.jar"), script.resolveSibling("kotlin-stdlib.jar"))
        val old = FileTime.fromMillis(1_000_000_000_000)
        files.forEach { Files.setLastModifiedTime(it, old) }

        install()

        assertEquals(List(3) { old }, files.map { Files.getLastModifiedTime(it) }, "not written again")
    }

    @Test
    fun `a file that cannot be put in place fails the install and leaves no temporary file`() {
        val script = install()
        val jar = script.resolveSibling("askpass.jar")
        Files.delete(jar)
        // A folder that is not empty cannot be replaced.
        jar.resolve("in-the-way").createDirectories()

        assertFailsWith<IOException> { install() }

        assertEquals(setOf("askpass", "askpass.jar", "kotlin-stdlib.jar"), names(script.parent))
    }

    private fun names(directory: Path) = directory.listDirectoryEntries().map { it.fileName.toString() }.toSet()
}
