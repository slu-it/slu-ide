package dev.sluide.java

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaWorkspacesTest {
    @TempDir
    lateinit var temp: Path

    private val workspaces by lazy { JavaWorkspaces(temp.resolve("workspaces")) }

    private fun config(vararg files: Pair<String, String>): Path {
        val folder = temp.resolve("config_x").createDirectories()
        files.forEach { (name, text) -> folder.resolve(name).also { it.parent.createDirectories() }.writeText(text) }
        return folder
    }

    @Test
    fun `the folder is the name and twelve hex of the path hash`() {
        val folder = workspaces.folderFor(Path.of("/home/me/projects/shop"))
        // sha256 of "/home/me/projects/shop"
        val expected = MessageDigest.getInstance("SHA-256").digest("/home/me/projects/shop".toByteArray())
        val hex = expected.joinToString("") { "%02x".format(it) }.take(12)
        assertEquals(temp.resolve("workspaces/shop-$hex"), folder)
    }

    @Test
    fun `the hash is fixed for a fixed path`() {
        assertEquals("shop-f6845106c6ab", workspaces.folderFor(Path.of("/home/me/projects/shop")).fileName.toString())
    }

    @Test
    fun `the path is normalized before hashing`() {
        assertEquals(workspaces.folderFor(Path.of("/a/b")), workspaces.folderFor(Path.of("/a/x/../b")))
        assertNotEquals(workspaces.folderFor(Path.of("/a/b")), workspaces.folderFor(Path.of("/a/c")))
    }

    @Test
    fun `other characters in the name become underscores`() {
        val name = workspaces.folderFor(Path.of("/x/my app (1)+$")).fileName.toString()
        assertTrue(name.startsWith("my_app__1___-"), name)
    }

    @Test
    fun `allowed characters stay`() {
        assertTrue(
            workspaces
                .folderFor(Path.of("/x/A-b_c.1"))
                .fileName
                .toString()
                .startsWith("A-b_c.1-"),
        )
    }

    @Test
    fun `the name is cut at forty characters`() {
        val name = workspaces.folderFor(Path.of("/x/" + "a".repeat(60))).fileName.toString()
        assertTrue(name.startsWith("a".repeat(40) + "-"), name)
        assertEquals(40 + 1 + 12, name.length)
    }

    @Test
    fun `a folder without a name is called project`() {
        assertTrue(
            workspaces
                .folderFor(Path.of("/"))
                .fileName
                .toString()
                .startsWith("project-"),
        )
    }

    @Test
    fun `prepare creates data, the project path, a timestamp and the config copy`() {
        val before = Instant.now().minusSeconds(1)
        val prepared = workspaces.prepare(Path.of("/p/shop"), config("a.ini" to "A", "sub/b.ini" to "B"), "1.63.0")
        assertEquals(workspaces.folderFor(Path.of("/p/shop")), prepared.folder)
        assertEquals(prepared.folder.resolve("data"), prepared.data)
        assertEquals(prepared.folder.resolve("config-1.63.0"), prepared.config)
        assertTrue(Files.isDirectory(prepared.data))
        assertEquals("/p/shop", prepared.folder.resolve("project-path.txt").readText())
        assertEquals("A", prepared.config.resolve("a.ini").readText())
        assertEquals("B", prepared.config.resolve("sub/b.ini").readText())
        val used = workspaces.list().single().lastUsed
        assertTrue(used != null && used >= before && used <= Instant.now().plusSeconds(1), used.toString())
    }

    @Test
    fun `prepare twice keeps an existing config copy and the data`() {
        val source = config("a.ini" to "A")
        val first = workspaces.prepare(Path.of("/p/shop"), source, "k")
        first.data.resolve("index").writeText("keep")
        first.config.resolve("a.ini").writeText("edited")
        val second = workspaces.prepare(Path.of("/p/shop"), source, "k")
        assertEquals(first, second)
        assertEquals("keep", second.data.resolve("index").readText())
        assertEquals("edited", second.config.resolve("a.ini").readText())
    }

    @Test
    fun `prepare replaces config folders of another key`() {
        val source = config("a.ini" to "A")
        val old = workspaces.prepare(Path.of("/p/shop"), source, "old")
        old.config
            .resolve("sub")
            .createDirectories()
            .resolve("x")
            .writeText("x")
        val next = workspaces.prepare(Path.of("/p/shop"), source, "new")
        assertFalse(old.config.exists())
        assertTrue(next.config.resolve("a.ini").exists())
        assertTrue(next.data.exists())
    }

    @Test
    fun `the config key is sanitized`() {
        val prepared = workspaces.prepare(Path.of("/p/shop"), config("a" to "A"), "../../evil key")
        assertEquals(prepared.folder, prepared.config.parent)
        assertEquals("config-.._.._evil_key", prepared.config.fileName.toString())
    }

    @Test
    fun `prepare does not follow a link in the config source`() {
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("secret").writeText("s")
        val source = config("a" to "A")
        Files.createSymbolicLink(source.resolve("link"), outside)
        val prepared = workspaces.prepare(Path.of("/p/shop"), source, "k")
        assertTrue(Files.isSymbolicLink(prepared.config.resolve("link")))
        assertEquals("s", outside.resolve("secret").readText())
    }

    @Test
    fun `a config source that is a link is copied as a folder`() {
        val real = config("a.ini" to "A")
        val link = Files.createSymbolicLink(temp.resolve("config_link"), real)
        val prepared = workspaces.prepare(Path.of("/p/shop"), link, "k")
        assertFalse(Files.isSymbolicLink(prepared.config))
        assertEquals("A", prepared.config.resolve("a.ini").readText())
    }

    @Test
    fun `list gives path, size and last use of every workspace`() {
        val a = workspaces.prepare(Path.of("/p/a"), config("c" to "12345"), "k")
        a.data.resolve("big").writeText("x".repeat(1000))
        workspaces.prepare(Path.of("/p/b"), config("c" to ""), "k")
        val byPath = workspaces.list().associateBy { it.projectPath }
        assertEquals(setOf(Path.of("/p/a"), Path.of("/p/b")), byPath.keys)
        val sizeA = byPath.getValue(Path.of("/p/a")).bytes
        val sizeB = byPath.getValue(Path.of("/p/b")).bytes
        assertEquals(1000 + 5, sizeA - sizeB)
        assertEquals(a.folder, byPath.getValue(Path.of("/p/a")).folder)
    }

    @Test
    fun `a size counts the files of a linked folder not at all`() {
        val prepared = workspaces.prepare(Path.of("/p/a"), config("c" to ""), "k")
        val before = workspaces.list().single().bytes
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("huge").writeText("x".repeat(100_000))
        Files.createSymbolicLink(prepared.data.resolve("link"), outside)
        assertTrue(workspaces.list().single().bytes < before + 10_000)
    }

    @Test
    fun `a workspace without a readable project path lists null`() {
        val folder = workspaces.root.resolve("odd-123456789abc").createDirectories()
        folder.resolve("x").writeText("1")
        val info = workspaces.list().single()
        assertNull(info.projectPath)
        assertNull(info.lastUsed)
        assertEquals(1L, info.bytes)
    }

    @Test
    fun `list of a missing root is empty`() {
        assertEquals(emptyList(), workspaces.list())
    }

    @Test
    fun `delete removes the folder with everything in it`() {
        val prepared = workspaces.prepare(Path.of("/p/a"), config("c" to "x"), "k")
        val other = workspaces.prepare(Path.of("/p/b"), config("c" to "x"), "k")
        workspaces.delete(prepared.folder)
        assertFalse(prepared.folder.exists())
        assertTrue(other.folder.exists())
    }

    @Test
    fun `delete refuses a folder that is no direct child of the root`() {
        val prepared = workspaces.prepare(Path.of("/p/a"), config("c" to "x"), "k")
        assertFailsWith<IllegalArgumentException> { workspaces.delete(prepared.data) }
        assertFailsWith<IllegalArgumentException> { workspaces.delete(workspaces.root) }
        assertFailsWith<IllegalArgumentException> { workspaces.delete(temp) }
        assertFailsWith<IllegalArgumentException> { workspaces.delete(prepared.folder.resolve("../../config_x")) }
        assertTrue(prepared.data.exists())
        assertTrue(temp.resolve("config_x").exists())
    }

    @Test
    fun `delete does not follow a link out of the root`() {
        val prepared = workspaces.prepare(Path.of("/p/a"), config("c" to "x"), "k")
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("k")
        Files.createSymbolicLink(prepared.data.resolve("link"), outside)
        workspaces.delete(prepared.folder)
        assertFalse(prepared.folder.exists())
        assertEquals("k", outside.resolve("keep").readText())
    }

    @Test
    fun `delete of a link as workspace removes only the link`() {
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("k")
        workspaces.root.createDirectories()
        val link = Files.createSymbolicLink(workspaces.root.resolve("linked-123456789abc"), outside)
        workspaces.delete(link)
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("k", outside.resolve("keep").readText())
    }

    @Test
    fun `deleteData removes the data folder and keeps the project path, the config and the init script`() {
        val window = Path.of("/p/a")
        val prepared = workspaces.prepare(window, config("c" to "x"), "k")
        prepared.data
            .resolve(".metadata/.lock")
            .also { it.parent.createDirectories() }
            .writeText("")
        prepared.folder.resolve("init.gradle").writeText("script")
        val other = workspaces.prepare(Path.of("/p/b"), config("c" to "x"), "k")

        workspaces.deleteData(window)

        assertFalse(prepared.data.exists())
        assertEquals("/p/a", prepared.folder.resolve("project-path.txt").readText())
        assertEquals("x", prepared.config.resolve("c").readText())
        assertEquals("script", prepared.folder.resolve("init.gradle").readText())
        assertTrue(other.data.exists(), "the data of another window")
    }

    @Test
    fun `deleteData removes a link inside data and not its target`() {
        val window = Path.of("/p/a")
        val prepared = workspaces.prepare(window, config("c" to "x"), "k")
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("keep").writeText("k")
        Files.createSymbolicLink(prepared.data.resolve("link"), outside)

        workspaces.deleteData(window)

        assertFalse(prepared.data.exists())
        assertEquals("k", outside.resolve("keep").readText())
    }

    @Test
    fun `deleteData of a workspace without data does nothing`() {
        val window = Path.of("/p/a")
        val prepared = workspaces.prepare(window, config("c" to "x"), "k")
        workspaces.deleteData(window)

        workspaces.deleteData(window)

        assertTrue(prepared.config.exists())
    }

    @Test
    fun `a stale partial config copy does not leak into the new one`() {
        val source = config("a.ini" to "A")
        val folder = workspaces.folderFor(Path.of("/p/shop"))
        folder
            .resolve("config-k.partial")
            .createDirectories()
            .resolve("stale")
            .writeText("old")
        val prepared = workspaces.prepare(Path.of("/p/shop"), source, "k")
        assertFalse(prepared.config.resolve("stale").exists())
        assertTrue(prepared.config.resolve("a.ini").exists())
    }

    @Test
    fun `list leaves out plain files in the root`() {
        workspaces.root
            .createDirectories()
            .resolve("stray.txt")
            .writeText("x")
        assertEquals(emptyList(), workspaces.list())
    }

    @Test
    fun `an empty project path file lists null`() {
        val folder = workspaces.root.resolve("odd-123456789abc").createDirectories()
        folder.resolve("project-path.txt").writeText("  \n")
        assertNull(workspaces.list().single().projectPath)
    }
}
