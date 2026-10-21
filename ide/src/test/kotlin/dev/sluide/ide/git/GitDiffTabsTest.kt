package dev.sluide.ide.git

import dev.sluide.git.GitChange
import dev.sluide.gitlog.WorkingTreeFileDiff
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitDiffTabsTest {
    @TempDir
    lateinit var root: Path

    private fun diff(
        path: String,
        change: GitChange = GitChange.MODIFIED,
    ) = WorkingTreeFileDiff(root, path, null, change, "abc", "abc", "", "", emptyList())

    @Test
    fun `a regular file on disk is the live file`() {
        root.resolve("src").createDirectories()
        val file = root.resolve("src/A.kt").also { it.writeText("x") }

        assertEquals(file, liveFile(diff("src/A.kt")))
    }

    @Test
    fun `a deleted file has no live file even if one is on disk`() {
        root.resolve("A.kt").writeText("x")

        assertNull(liveFile(diff("A.kt", GitChange.DELETED)))
    }

    @Test
    fun `a missing file or a directory has no live file`() {
        root.resolve("dir").createDirectories()

        assertNull(liveFile(diff("missing.kt")))
        assertNull(liveFile(diff("dir")))
    }

    @Test
    fun `a symlink to a regular file is not the live file`() {
        val target = root.resolve("target.txt").also { it.writeText("x") }
        val link = root.resolve("link.txt")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links are not supported")
        } catch (_: IOException) {
            assumeTrue(false, "symbolic links cannot be created")
        }

        assertNull(liveFile(diff("link.txt")))
    }

    // nameOf's fallback (non-ASCII characters replaced) only applies where the JVM cannot encode them in file names,
    // i.e. a plain ASCII locale; on a UTF-8 JVM every name that is not invalid anyway (NUL) is accepted as it is.
    @Test
    fun `a name the JVM can name stays as it is`() {
        assertEquals(Path.of("src/Main.kt"), nameOf("src/Main.kt"))
    }
}
