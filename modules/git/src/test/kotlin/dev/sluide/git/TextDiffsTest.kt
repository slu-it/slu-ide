package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TextDiffsTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())

    @Test
    fun `the texts are compared in a folder below the one given, which is empty again afterwards`() {
        assumeTrue(git.available, "git is not installed")
        val temporary = folder.resolve("temporary").also { Files.createDirectories(it) }

        val hunks = git.diffTexts("a\n".toByteArray(), "b\n".toByteArray(), null, temporary)

        assertNotNull(hunks)
        assertEquals(1, hunks.size)
        assertEquals(emptyList(), Files.list(temporary).use { it.toList() })
    }

    @Test
    fun `without a folder to work in nothing is compared`() {
        assumeTrue(git.available, "git is not installed")

        assertNull(git.diffTexts("a\n".toByteArray(), "b\n".toByteArray(), null, folder.resolve("missing")))
    }
}
