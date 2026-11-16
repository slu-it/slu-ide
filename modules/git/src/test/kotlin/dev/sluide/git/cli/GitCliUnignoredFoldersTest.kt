package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.testGitEnvironment
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [unignoredFolders] with the real git; skipped if git is not installed. The service test has the ignore rules. */
class GitCliUnignoredFoldersTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())

    private fun repository(): Path {
        assumeTrue(git.available, "git is not installed")
        val repo = folder.resolve("repo").createDirectories()
        val process =
            ProcessBuilder("git", "init", "-q")
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.inputStream.readAllBytes()
        assertEquals(0, process.waitFor(), "git init")
        return repo
    }

    private fun Path.files(
        folder: String,
        vararg names: String,
    ): Path = resolve(folder).createDirectories().also { dir -> names.forEach { dir.resolve(it).writeText("x") } }

    @Test
    fun `lists the folders with an untracked file, in the order asked`() {
        val repo = repository()
        val target = repo.files("target", "A.out")
        val build = repo.files("build/jdtls-bin", "B.out")
        val empty = repo.files("empty")

        assertEquals(listOf(build, target), git.unignoredFolders(repo, listOf(build, empty, target), null))
    }

    // Pathspec magic in a project folder's name (`:!x` would exclude, `:(glob)x` is a glob) is taken literally.
    @Test
    fun `a folder named like a pathspec is listed by its name`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "a name starting with : is invalid on Windows")
        val repo = repository()
        val names = listOf(":!x", ":(glob)y", ":(top)z")
        val targets = names.map { repo.files("$it/target", "A.out") }

        assertEquals(targets, git.unignoredFolders(repo, targets, null))
    }

    @Test
    fun `no folders list nothing`() {
        assertEquals(emptyList(), git.unignoredFolders(repository(), emptyList(), null))
    }

    @Test
    fun `no answer for the repository's root or a folder outside it`() {
        val repo = repository()
        val outside = folder.files("elsewhere", "A.out")
        repo.files("target", "A.out")

        assertNull(git.unignoredFolders(repo, listOf(repo), null), "the root itself")
        assertNull(git.unignoredFolders(repo, listOf(repo.resolve("target"), outside), null), "one outside")
    }

    @Test
    fun `no answer when git fails, which is no repository here`() {
        assumeTrue(git.available, "git is not installed")
        val plain = folder.files("plain", "A.out")
        val inside = plain.files("target", "A.out")

        assertNull(git.unignoredFolders(plain, listOf(inside), null))
    }

    @Test
    fun `no answer when cancelled`() {
        val repo = repository()
        val target = repo.files("target", "A.out")

        assertNull(git.unignoredFolders(repo, listOf(target), GitCancellation().apply { cancel() }))
    }

    @Test
    fun `a listing cut off for its size still names the folder, since one record proves it`() {
        val repo = repository()
        // More than a pipe holds, so that git is still writing when it is stopped (and exits with a signal).
        val names = (1..3000).map { "File-with-a-rather-long-name-$it.out" }.toTypedArray()
        val target = repo.files("target", *names)

        assertEquals(listOf(target), git.unignoredFolders(repo, listOf(target), null, maxListing = 200))
    }

    @Test
    fun `no answer for a git that does not exist`() {
        val repo = folder.resolve("repo").createDirectories()
        val missing = GitCli(folder.resolve("no-such-git").toString(), System.getenv())

        assertNull(missing.unignoredFolders(repo, listOf(repo.files("target", "A.out")), null))
    }
}
