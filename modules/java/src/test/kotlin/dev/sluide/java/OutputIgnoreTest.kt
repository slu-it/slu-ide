package dev.sluide.java

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputIgnoreTest {
    @TempDir
    lateinit var dir: Path

    private val repo get() = dir.resolve("repo").createDirectories()

    private fun folder(path: String): Path = repo.resolve(path)

    @Test
    fun `Gradle compiles into build jdtls-bin of every project and Maven into target`() {
        val projects = listOf(folder("."), folder("app"), folder("app"))

        assertEquals(
            listOf(folder("./build/jdtls-bin").normalize(), folder("app/build/jdtls-bin")),
            OutputIgnore.outputFolders(BuildTool.GRADLE, projects).map { it.normalize() },
        )
        assertEquals(
            listOf(folder("target"), folder("app/target")),
            OutputIgnore.outputFolders(BuildTool.MAVEN, projects).map { it.normalize() },
        )
    }

    @Test
    fun `the target is the nearest gitignore from the build root up to the repository root`() {
        val build = folder("a/b").createDirectories()
        folder(".gitignore").writeText("")
        folder("a/.gitignore").writeText("")
        assertEquals(folder("a/.gitignore"), OutputIgnore.target(build, repo))

        folder("a/b/.gitignore").writeText("")
        assertEquals(folder("a/b/.gitignore"), OutputIgnore.target(build, repo))
    }

    @Test
    fun `a gitignore above the repository root is not taken, a new one goes into the build root`() {
        dir.resolve(".gitignore").writeText("")
        val build = folder("a").createDirectories()

        assertEquals(folder("a/.gitignore"), OutputIgnore.target(build, repo))
    }

    @Test
    fun `a gitignore that is a symbolic link is the target, to be refused`() {
        val build = folder("a").createDirectories()
        val real = dir.resolve("real").apply { writeText("") }
        folder("a/.gitignore").createSymbolicLinkPointingTo(real)

        assertEquals(folder("a/.gitignore"), OutputIgnore.target(build, repo))
    }

    @Test
    fun `a folder called gitignore is passed over`() {
        val build = folder("a").createDirectories()
        folder("a/.gitignore").createDirectories()
        folder(".gitignore").writeText("")

        assertEquals(folder(".gitignore"), OutputIgnore.target(build, repo))
    }

    @ParameterizedTest
    @CsvSource(
        "plain, plain",
        "#x, \\#x",
        "!x, \\!x",
        "a*b, a\\*b",
        "a?b, a\\?b",
        "[x], \\[x]",
        "back\\slash, back\\\\slash",
        "ünï☕, ünï☕",
        "a#b!c, a#b!c",
    )
    fun `escapes the specials of a path segment`(
        segment: String,
        escaped: String,
    ) = assertEquals(escaped, OutputIgnore.escape(segment))

    @Test
    fun `escapes every trailing space and no other`() {
        assertEquals("sp ace\\ \\ ", OutputIgnore.escape("sp ace  "))
        assertEquals(" lead", OutputIgnore.escape(" lead"))
    }

    @Test
    fun `a line is anchored, relative to the gitignore's folder and ends in a slash`() {
        assertEquals("/build/jdtls-bin/", OutputIgnore.line(repo, folder("build/jdtls-bin")))
        assertEquals("/app/build/jdtls-bin/", OutputIgnore.line(repo, folder("app/build/jdtls-bin")))
        assertEquals("/build/jdtls-bin/", OutputIgnore.line(folder("app"), folder("app/build/jdtls-bin")))
        assertEquals("/target/", OutputIgnore.line(repo, folder("target")))
        assertEquals("/\\#x/\\!y z\\ /", OutputIgnore.line(repo, folder("#x/!y z ")))
    }

    @Test
    fun `no line for a folder with a line break in its name or outside the gitignore's folder`() {
        assertNull(OutputIgnore.line(repo, folder("a\nb")))
        assertNull(OutputIgnore.line(repo, folder("a\rb")))
        assertNull(OutputIgnore.line(folder("app"), folder("other/target")))
        assertNull(OutputIgnore.line(folder("app"), folder("app")))
    }

    // Checked against the sandbox's git: each hostile name written as a line ignores exactly that folder.
    @ParameterizedTest
    @ValueSource(strings = ["#x", "!x", "a*b", "sp ace ", "back\\slash", "[x]", "a?b"])
    fun `git ignores the folder a written line names`(name: String) {
        val work = repo

        // No user configuration or global excludes (a Java developer's often list `*.class`): git decides alone.
        fun git(vararg arguments: String) =
            ProcessBuilder(listOf("git") + arguments)
                .directory(work.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().keys.removeIf { it.startsWith("GIT_") || it == "HOME" || it == "XDG_CONFIG_HOME" }
                    environment()["HOME"] = dir.toString()
                    environment()["XDG_CONFIG_HOME"] = dir.resolve("xdg").toString()
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
                .also { it.inputStream.readAllBytes() }
                .waitFor()
        assumeTrue(runCatching { git("init", "-q") == 0 }.getOrDefault(false), "git is not installed")
        val out = work.resolve(name).resolve("out").createDirectories()
        val other = work.resolve("zz").resolve("out").createDirectories()
        assertNull(OutputIgnore.add(work.resolve(".gitignore"), work, listOf(out)))

        fun ignored(path: Path): Boolean =
            git("check-ignore", "-q", "--no-index", "--", "${work.relativize(path)}/x.out") == 0
        assertTrue(ignored(out), "the folder itself")
        assertTrue(!ignored(other), "a folder that is not named")
    }

    private fun add(
        vararg folders: String,
        target: Path = folder(".gitignore"),
    ): String? = OutputIgnore.add(target, repo, folders.map(::folder))

    @Test
    fun `creates the gitignore when there is none`() {
        assertNull(add("build/jdtls-bin", "app/build/jdtls-bin"))

        assertEquals("/build/jdtls-bin/\n/app/build/jdtls-bin/\n", folder(".gitignore").readText())
    }

    @Test
    fun `appends to a file and keeps what it has`() {
        folder(".gitignore").writeText("*.log\n")

        assertNull(add("target"))

        assertEquals("*.log\n/target/\n", folder(".gitignore").readText())
    }

    @Test
    fun `keeps CRLF when the first line ends so`() {
        folder(".gitignore").writeText("*.log\r\nout\n")

        assertNull(add("target"))

        assertEquals("*.log\r\nout\n/target/\r\n", folder(".gitignore").readText())
    }

    @Test
    fun `keeps LF when only a later line ends in CRLF`() {
        folder(".gitignore").writeText("*.log\nout\r\n")

        assertNull(add("target"))

        assertEquals("*.log\nout\r\n/target/\n", folder(".gitignore").readText())
    }

    @Test
    fun `ends a last line without newline first, in the file's line ending`() {
        folder(".gitignore").writeText("*.log")
        assertNull(add("target"))
        assertEquals("*.log\n/target/\n", folder(".gitignore").readText())

        folder(".gitignore").writeText("*.log\r\nout")
        assertNull(add("target"))
        assertEquals("*.log\r\nout\r\n/target/\r\n", folder(".gitignore").readText())
    }

    @Test
    fun `keeps a byte order mark`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        folder(".gitignore").writeBytes(bom + "*.log\n".toByteArray())

        assertNull(add("target"))

        assertContentEquals(bom + "*.log\n/target/\n".toByteArray(), folder(".gitignore").readBytes())
    }

    @Test
    fun `finds a line behind a byte order mark and in CRLF as present`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val old = bom + "/target/\r\n".toByteArray()
        folder(".gitignore").writeBytes(old)

        assertNull(add("target"))

        assertContentEquals(old, folder(".gitignore").readBytes())
    }

    @Test
    fun `a line that is present exactly is not added again, a similar one is`() {
        folder(".gitignore").writeText("/build/jdtls-bin/\nbuild/jdtls-bin\n /app/build/jdtls-bin/\n")

        assertNull(add("build/jdtls-bin", "app/build/jdtls-bin", "target", "target"))

        assertEquals(
            "/build/jdtls-bin/\nbuild/jdtls-bin\n /app/build/jdtls-bin/\n/app/build/jdtls-bin/\n/target/\n",
            folder(".gitignore").readText(),
        )
    }

    @Test
    fun `a line that is present in a file without a trailing newline leaves it as it is`() {
        folder(".gitignore").writeText("*.log\n/target/")

        assertNull(add("target"))

        assertEquals("*.log\n/target/", folder(".gitignore").readText())
    }

    @Test
    fun `a file shorter than a byte order mark is no byte order mark`() {
        folder(".gitignore").writeText("#")

        assertNull(add("target"))

        assertEquals("#\n/target/\n", folder(".gitignore").readText())
    }

    @Test
    fun `an empty file gets no leading newline`() {
        folder(".gitignore").writeText("")

        assertNull(add("target"))

        assertEquals("/target/\n", folder(".gitignore").readText())
    }

    @Test
    fun `keeps the permissions and leaves no temporary file`() {
        assumeTrue(Files.getFileStore(repo).supportsFileAttributeView("posix"), "no POSIX permissions")
        folder(".gitignore").writeText("*.log\n")
        Files.setPosixFilePermissions(folder(".gitignore"), PosixFilePermissions.fromString("rw-rw----"))

        assertNull(add("target"))

        assertEquals(
            PosixFilePermissions.fromString("rw-rw----"),
            Files.getPosixFilePermissions(folder(".gitignore")),
        )
        assertEquals(listOf(".gitignore"), repo.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `refuses a symbolic link and leaves its target`() {
        val real = dir.resolve("real").apply { writeText("*.log\n") }
        folder(".gitignore").createSymbolicLinkPointingTo(real)

        val failure = add("target")

        assertEquals("Cannot add to ${folder(".gitignore")}: it is a symbolic link", failure)
        assertEquals("*.log\n", real.readText())
        assertTrue(Files.isSymbolicLink(folder(".gitignore")))
    }

    @Test
    fun `refuses a gitignore under a git folder`() {
        val inside = folder("sub/.git/info").createDirectories().resolve(".gitignore")

        val failure = add("sub/target", target = inside)

        assertEquals("Cannot add to $inside: it is inside a .git folder", failure)
        assertTrue(!inside.exists())
    }

    @Test
    fun `refuses a gitignore in a folder that links into a git folder`() {
        val git = folder(".git/info").createDirectories().parent
        folder("link").createSymbolicLinkPointingTo(git)

        val failure = add("link/target", target = folder("link/.gitignore"))

        assertEquals("Cannot add to ${folder("link/.gitignore")}: it is inside a .git folder", failure)
        assertTrue(!git.resolve(".gitignore").exists())
    }

    @Test
    fun `a folder holds a file when one lies in it or below, however deep within the limit`() {
        val out = folder("build/jdtls-bin")
        assertTrue(!OutputIgnore.hasFile(out), "missing")
        out.createDirectories()
        assertTrue(!OutputIgnore.hasFile(out), "empty")
        out.resolve("classes/main").createDirectories()
        assertTrue(!OutputIgnore.hasFile(out), "empty subfolders only, which git does not list")

        out.resolve("classes/main/A.class").writeText("x")

        assertTrue(OutputIgnore.hasFile(out))
    }

    @Test
    fun `a file in a deep package folder counts with the default limits`() {
        val out = folder("build/jdtls-bin/main")
        out.resolve("com/example/acme/shop/order/billing/invoice/pdf/render/A.class").file("x")

        assertTrue(OutputIgnore.hasFile(folder("build/jdtls-bin")))
    }

    @Test
    fun `an output folder that is itself a link has no file, as git lists the link and not what is in it`() {
        val real = dir.resolve("real").createDirectories().also { it.resolve("A.class").writeText("x") }
        folder("target").createSymbolicLinkPointingTo(real)

        assertTrue(!OutputIgnore.hasFile(folder("target")))
    }

    @Test
    fun `a file below the depth limit is not seen`() {
        val out = folder("out")
        out
            .resolve("a/b")
            .createDirectories()
            .resolve("A.class")
            .writeText("x")

        assertTrue(!OutputIgnore.hasFile(out, maxDepth = 1))
        assertTrue(OutputIgnore.hasFile(out, maxDepth = 2))
    }

    @Test
    fun `no more entries than the limit are looked at`() {
        val out = folder("out")
        out
            .resolve("a")
            .createDirectories()
            .resolve("A.class")
            .writeText("x")

        assertTrue(!OutputIgnore.hasFile(out, maxEntries = 1))
        assertTrue(OutputIgnore.hasFile(out, maxEntries = 2))
    }

    @Test
    fun `a link to a folder with a file is not followed`() {
        val real = dir.resolve("real").createDirectories().also { it.resolve("A.class").writeText("x") }
        val out = folder("out").createDirectories()
        out.resolve("link").createSymbolicLinkPointingTo(real)

        assertTrue(!OutputIgnore.hasFile(out))
    }

    @Test
    fun `refuses a gitignore that is not below the repository root`() {
        val outside = dir.resolve(".gitignore")

        val failure = add("target", target = outside)

        assertEquals("Cannot add to $outside: it is not inside the repository", failure)
        assertTrue(!outside.exists())
    }

    @Test
    fun `refuses a gitignore in a folder that links out of the repository`() {
        val outside = dir.resolve("outside").createDirectories()
        folder("link").createSymbolicLinkPointingTo(outside)

        val failure = add("link/target", target = folder("link/.gitignore"))

        assertEquals("Cannot add to ${folder("link/.gitignore")}: it is not inside the repository", failure)
        assertTrue(!outside.resolve(".gitignore").exists())
    }

    @Test
    fun `refuses a gitignore that reaches out of the repository by dots`() {
        val sneaky = folder("a/../../.gitignore")

        val failure = add("target", target = sneaky)

        assertNotNull(failure)
        assertTrue(!dir.resolve(".gitignore").exists())
    }

    @Test
    fun `refuses a folder with a line break in its name and writes nothing`() {
        folder(".gitignore").writeText("*.log\n")

        val failure = add("target", "a\nb")

        assertEquals("Cannot add to ${folder(".gitignore")}: ${folder("a\nb")} has a line break in its name", failure)
        assertEquals("*.log\n", folder(".gitignore").readText())
    }

    @Test
    fun `refuses a folder outside the gitignore's folder`() {
        val failure = add("other/target", target = folder("app/.gitignore").also { it.parent.createDirectories() })

        assertEquals(
            "Cannot add to ${folder("app/.gitignore")}: ${folder("other/target")} is not below ${folder("app")}",
            failure,
        )
    }

    @Test
    fun `reports a failed write with its reason`() {
        // A directory where the file should be: neither readable as a file nor replaceable.
        folder(".gitignore").createDirectories()

        val failure = add("target")

        assertNotNull(failure)
        assertTrue(failure.startsWith("Cannot add to ${folder(".gitignore")}: "), failure)
    }
}
