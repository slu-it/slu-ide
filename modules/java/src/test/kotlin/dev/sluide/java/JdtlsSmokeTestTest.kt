package dev.sluide.java

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The smoke test on a real jdtls: `SLUIDE_TEST_JDTLS` names an unpacked jdtls folder; skipped without it. */
class JdtlsSmokeTestTest {
    @TempDir
    lateinit var dir: Path

    /** Every file below the `config_*` folders of [home] with its bytes: what a workspace copies its config from. */
    private fun configs(home: Path): Map<String, List<Byte>> =
        Files
            .list(home)
            .use { children ->
                children.filter { it.name.startsWith("config_") }.toList()
            }.flatMap { config ->
                Files.walk(config).use { paths ->
                    paths
                        .filter { Files.isRegularFile(it) }
                        .map { home.relativize(it).toString() to Files.readAllBytes(it).toList() }
                        .toList()
                }
            }.toMap()

    @Test
    fun `the real jdtls answers the smoke test's initialize and leaves its install and the smoke folder unchanged`() {
        val unpacked = System.getenv("SLUIDE_TEST_JDTLS")
        assumeTrue(unpacked != null, "SLUIDE_TEST_JDTLS names no unpacked jdtls")
        val real = Path.of(checkNotNull(unpacked))
        // An install of its own: the config_* folders and the launcher are copies, the bundles are linked.
        val install = dir.resolve("jdtls").createDirectories()
        install.resolve("bin").createDirectories()
        Files.copy(real.resolve("bin/jdtls"), install.resolve("bin/jdtls"))
        Files.list(real).use { children ->
            for (child in children.toList()) {
                if (child.name.startsWith("config_")) {
                    Files.walk(child).use { paths ->
                        for (path in paths.toList()) {
                            val target = install.resolve(real.relativize(path).toString())
                            if (Files.isDirectory(path)) target.createDirectories() else Files.copy(path, target)
                        }
                    }
                } else if (child.name == "plugins" || child.name == "features") {
                    Files.createSymbolicLink(install.resolve(child.name), child)
                }
            }
        }
        val before = configs(install)
        val smokeRoot = dir.resolve("smoke")
        val tool = JdtlsTool.tool(smokeRoot = smokeRoot)

        val reason = JdtlsTool.smokeTest(tool, smokeRoot)(install.resolve("bin/jdtls"))

        assertNull(reason)
        assertEquals(before.keys, configs(install).keys, "no file came or went")
        assertEquals(before, configs(install), "byte for byte, what JavaWorkspaces.prepare copies")
        assertFalse(Files.exists(smokeRoot), "the cache of the run is deleted")
    }
}
