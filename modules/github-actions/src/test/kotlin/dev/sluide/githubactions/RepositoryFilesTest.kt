package dev.sluide.githubactions

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RepositoryFilesTest {
    @TempDir
    lateinit var dir: Path

    private val repo by lazy { dir.resolve("repo").createDirectories() }
    private val files by lazy { RepositoryFiles(repo) }

    private fun outside(path: String) =
        assertEquals(RepositoryFiles.OUTSIDE, assertFailsWith<IOException> { files.local(path) }.message, path)

    @Test
    fun `reads files below the root as UTF-8`() {
        repo
            .resolve(".github/actions/setup")
            .createDirectories()
            .resolve("action.yml")
            .writeText("name: Größe")

        assertEquals("name: Größe", files.local(".github/actions/setup/action.yml"))
        assertEquals("name: Größe", files.local("./.github/actions/../actions/setup/action.yml"))
    }

    @Test
    fun `a root reached through a symbolic link reads its files`() {
        repo.resolve("a.yml").writeText("name: a")
        dir.resolve("secret.yml").writeText("secret")
        val linked = RepositoryFiles(dir.resolve("link").createSymbolicLinkPointingTo(repo))

        assertEquals("name: a", linked.local("a.yml"))
        assertEquals(
            RepositoryFiles.OUTSIDE,
            assertFailsWith<IOException> { linked.local("../secret.yml") }.message,
        )
    }

    @Test
    fun `a path that is no path is refused as invalid`() {
        assertEquals("invalid path", assertFailsWith<IOException> { files.local("a\u0000b") }.message)
    }

    @Test
    fun `missing files, folders and dangling links are none`() {
        repo.resolve("folder").createDirectories()
        Files.createSymbolicLink(repo.resolve("dangling.yml"), repo.resolve("gone.yml"))

        assertNull(files.local("missing.yml"))
        assertNull(files.local("missing/action.yml"))
        assertNull(files.local("folder"))
        assertNull(files.local("dangling.yml"))
        assertNull(RepositoryFiles(dir.resolve("no-repo")).local("action.yml"))
    }

    @Test
    fun `paths leaving the root are refused`() {
        dir.resolve("secret.yml").writeText("secret")

        outside("../secret.yml")
        outside("a/../../secret.yml")
        outside(dir.resolve("secret.yml").toString())
    }

    @Test
    fun `symbolic links out of the root are refused, links inside it are followed`() {
        dir
            .resolve("secret")
            .createDirectories()
            .resolve("action.yml")
            .writeText("secret")
        Files.createSymbolicLink(repo.resolve("escape"), dir.resolve("secret"))
        repo
            .resolve("real")
            .createDirectories()
            .resolve("action.yml")
            .writeText("inside")
        Files.createSymbolicLink(repo.resolve("alias"), repo.resolve("real"))

        outside("escape/action.yml")
        assertEquals("inside", files.local("alias/action.yml"))
    }

    @Test
    fun `files over 1 MB are refused`() {
        repo.resolve("big.yml").writeBytes(ByteArray(RepositoryFiles.MAX_BYTES + 1) { 'x'.code.toByte() })
        repo.resolve("fits.yml").writeBytes(ByteArray(RepositoryFiles.MAX_BYTES) { 'x'.code.toByte() })

        assertEquals("larger than 1 MB", assertFailsWith<IOException> { files.local("big.yml") }.message)
        assertEquals(RepositoryFiles.MAX_BYTES, files.local("fits.yml")?.length)
    }

    @Test
    fun `no repository reads nothing`() {
        assertNull(WorkflowSources.NONE.local("action.yml"))
    }
}
