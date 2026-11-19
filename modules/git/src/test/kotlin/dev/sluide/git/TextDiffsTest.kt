package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.GitCommandLogSink
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextDiffsTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())

    @Test
    fun `the texts are compared in a folder below the one given, which is empty again afterwards`() {
        assumeTrue(git.available, "git is not installed")
        val temporary = folder.resolve("temporary").also { Files.createDirectories(it) }
        val lines = CopyOnWriteArrayList<String>()
        val previous = GitCommandLogSink.sink
        GitCommandLogSink.sink = { _, message, _ -> lines += message }
        val hunks =
            try {
                git.diffTexts("a\n".toByteArray(), "b\n".toByteArray(), null, temporary)
            } finally {
                GitCommandLogSink.sink = previous
            }

        assertNotNull(hunks)
        assertEquals(1, hunks.size)
        assertEquals(emptyList(), Files.list(temporary).use { it.toList() })
        assertTrue(lines.any { "(in $temporary/sluide-diff" in it }, "git ran in a folder below it: $lines")
    }

    @Test
    fun `without a folder to work in nothing is compared`() {
        assumeTrue(git.available, "git is not installed")

        assertNull(git.diffTexts("a\n".toByteArray(), "b\n".toByteArray(), null, folder.resolve("missing")))
    }

    @Test
    fun `without a folder given the texts are still compared`() {
        assumeTrue(git.available, "git is not installed")

        assertEquals(1, git.diffTexts("a\n".toByteArray(), "b\n".toByteArray(), null, null)?.size)
    }
}
