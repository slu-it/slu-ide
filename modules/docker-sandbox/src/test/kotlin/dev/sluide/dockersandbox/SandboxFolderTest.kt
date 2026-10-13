package dev.sluide.dockersandbox

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SandboxFolderTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("users/me").createDirectories() }

    private fun check(folder: Path) = SandboxFolder.check(folder, home)

    @Test
    fun `folders below the home folder are allowed`() {
        assertNull(check(home.resolve("project").createDirectories()))
        assertNull(check(home.resolve("work/deep/project").createDirectories()))
    }

    @Test
    fun `the home folder itself is not allowed`() {
        val problem = check(home)
        assertEquals(
            "Docker sandboxes run only in folders below your home folder (${home.toRealPath()}), " +
                "not in ${home.toRealPath()}.",
            problem,
        )
        home.resolve("project").createDirectories()
        assertNotNull(check(home.resolve("project/..")), "a way back up to the home folder")
    }

    @Test
    fun `folders above the home folder are not allowed`() {
        assertNotNull(check(home.parent))
        assertNotNull(check(root))
        assertNotNull(check(root.root))
    }

    @Test
    fun `folders outside the home folder are not allowed`() {
        assertNotNull(check(root.resolve("elsewhere").createDirectories()))
        // Shares the name's beginning, but is no folder below the home folder.
        assertNotNull(check(root.resolve("users/me2/project").createDirectories()))
    }

    @Test
    fun `symbolic links count where they lead`() {
        val outside = root.resolve("outside").createDirectories()
        assertNotNull(check(home.resolve("link-out").createSymbolicLinkPointingTo(outside)))
        assertNotNull(check(home.resolve("link-home").createSymbolicLinkPointingTo(home)))

        val inside = home.resolve("project").createDirectories()
        assertNull(check(root.resolve("link-in").createSymbolicLinkPointingTo(inside)))
    }

    @Test
    fun `a missing folder is not allowed`() {
        assertEquals("The folder ${home.resolve("missing")} does not exist.", check(home.resolve("missing")))
    }

    @Test
    fun `a home folder reached through a symbolic link is resolved too`() {
        val linkedHome = root.resolve("home-link").createSymbolicLinkPointingTo(home)
        val project = home.resolve("project").createDirectories()

        assertNull(SandboxFolder.check(project, linkedHome))
        assertNotNull(SandboxFolder.check(home, linkedHome))
    }
}
