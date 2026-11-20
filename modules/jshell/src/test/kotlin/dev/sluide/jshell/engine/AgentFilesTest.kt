package dev.sluide.jshell.engine

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentFilesTest {
    @TempDir
    lateinit var root: Path

    private val agent = "agent classes".toByteArray()
    private val stdlib = ByteArray(300_000) { (it % 251).toByte() }

    private fun resources(
        agentBytes: ByteArray = agent,
        stdlibBytes: ByteArray = stdlib,
    ): (String) -> java.io.InputStream? =
        { name ->
            when (name) {
                "agent.jar" -> agentBytes.inputStream()
                "kotlin-stdlib.jar" -> stdlibBytes.inputStream()
                else -> null
            }
        }

    @Test
    fun `the files land in a folder named by their content hash, in class path order`() {
        val paths = AgentFiles.install(root, resources())
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, bytes) in listOf("agent.jar" to agent, "kotlin-stdlib.jar" to stdlib)) {
            digest.update("$name\u0000${bytes.size}\u0000".toByteArray())
            digest.update(bytes)
        }
        val prefix = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        assertEquals(
            listOf(root.resolve(prefix).resolve("agent.jar"), root.resolve(prefix).resolve("kotlin-stdlib.jar")),
            paths,
        )
        assertContentEquals(agent, paths.getOrNull(0)?.readBytes())
        assertContentEquals(stdlib, paths.getOrNull(1)?.readBytes())
    }

    @Test
    fun `other content goes to another folder`() {
        val first = AgentFiles.install(root, resources())
        val second = AgentFiles.install(root, resources(agentBytes = "other".toByteArray()))
        assertNotEquals(first.firstOrNull()?.parent, second.firstOrNull()?.parent)
        assertEquals(2, root.listDirectoryEntries().size)
    }

    @Test
    fun `the same bytes split differently between the files are other content`() {
        val first =
            AgentFiles.install(
                root,
                resources(agentBytes = "ab".toByteArray(), stdlibBytes = "c".toByteArray()),
            )
        val second =
            AgentFiles.install(
                root,
                resources(agentBytes = "a".toByteArray(), stdlibBytes = "bc".toByteArray()),
            )
        assertNotEquals(first.firstOrNull()?.parent, second.firstOrNull()?.parent)
    }

    @Test
    fun `installed files are not written again`() {
        val paths = AgentFiles.install(root, resources())
        val old = FileTime.fromMillis(1_000_000_000_000)
        paths.forEach { Files.setLastModifiedTime(it, old) }
        assertEquals(paths, AgentFiles.install(root, resources()))
        assertEquals(listOf(old, old), paths.map { Files.getLastModifiedTime(it) })
    }

    @Test
    fun `a damaged file is written again`() {
        val paths = AgentFiles.install(root, resources())
        assertEquals(2, paths.size)
        paths[1].writeBytes(stdlib.copyOf(1000))
        paths[0].writeBytes("agent clas!".toByteArray() + ByteArray(2))
        AgentFiles.install(root, resources())
        assertContentEquals(agent, paths[0].readBytes())
        assertContentEquals(stdlib, paths[1].readBytes())
    }

    @Test
    fun `a file that cannot be moved into place leaves no temporary file`() {
        val paths = AgentFiles.install(root, resources())
        assertEquals(2, paths.size)
        Files.delete(paths[0])
        Files.createDirectories(paths[0].resolve("child"))
        assertFailsWith<IOException> { AgentFiles.install(root, resources()) }
        assertEquals(
            listOf("agent.jar", "kotlin-stdlib.jar"),
            paths[0]
                .parent
                .listDirectoryEntries()
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun `a missing resource is an error naming it`() {
        val error =
            assertFailsWith<IOException> {
                AgentFiles.install(root) { name -> if (name == "agent.jar") agent.inputStream() else null }
            }
        assertEquals("The JShell agent's kotlin-stdlib.jar is missing from Thoreau", error.message)
    }

    @Test
    fun `two installs at once both end with whole files and leave no temporary file`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val results =
                List(2) {
                    pool.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            AgentFiles.install(root, resources())
                        },
                    )
                }.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(2, results[0].size)
            assertContentEquals(stdlib, results[0][1].readBytes())
            assertEquals(
                listOf("agent.jar", "kotlin-stdlib.jar"),
                results[0][0]
                    .parent
                    .listDirectoryEntries()
                    .map {
                        it.name
                    }.sorted(),
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `the module carries the agent built from its sources and kotlin-stdlib`() {
        val paths = AgentFiles.install(root)
        assertEquals(AgentFiles.NAMES, paths.map { it.name })
        assertTrue(paths.all { it.startsWith(root) })
        ZipFile(paths[0].toFile()).use { assertTrue(it.getEntry("dev/sluide/jshell/agent/Agent.class") != null) }
        ZipFile(paths[1].toFile()).use { assertTrue(it.getEntry("kotlin/Unit.class") != null) }
    }
}
