package dev.sluide.java

import dev.sluide.languageservers.ServerCommand
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [JdtlsLaunch] over a temp tree that has the layout of the real 1.63.0 archive (plugins, config_*, bin/jdtls). */
class JdtlsLaunchTest {
    @TempDir
    lateinit var dir: Path

    private val home get() = dir.resolve("jdtls")
    private val launcher get() = home.resolve("plugins/org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar")

    private fun unpack(
        launchers: List<String> = listOf("org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar"),
    ): Path {
        home.resolve("bin").createDirectories()
        home.resolve("bin/jdtls").writeText("#!/usr/bin/env python3\n")
        val plugins = home.resolve("plugins").createDirectories()
        // The real archive also has per-platform launchers and other bundles; only the plain one is the launcher.
        for (
        name in
        listOf(
            "org.eclipse.equinox.launcher.gtk.linux.aarch64_1.2.1700.v20260913-1737.jar",
            "org.eclipse.equinox.launcher.cocoa.macosx.aarch64_1.2.1700.v20260913-1737.jar",
            "com.google.gson_2.14.0.jar",
        ) + launchers
        ) {
            Files.createFile(plugins.resolve(name))
        }
        for (config in listOf("config_linux", "config_linux_arm", "config_mac", "config_mac_arm", "config_win")) {
            home.resolve(config).createDirectories()
        }
        return home.resolve("bin/jdtls")
    }

    @Test
    fun `home is the folder above bin when it has one launcher jar and a config for this OS`() {
        val executable = unpack()

        assertEquals(
            home,
            JdtlsLaunch.home(executable),
        )
    }

    @Test
    fun `home is null without a launcher jar, with two, without plugins and for a missing file`() {
        assertNull(JdtlsLaunch.home(home.resolve("bin/jdtls")), "nothing unpacked")

        unpack(launchers = emptyList())
        assertNull(JdtlsLaunch.home(home.resolve("bin/jdtls")), "no plain launcher jar")

        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_1.7.0.jar"))
        Files.createFile(home.resolve("plugins/org.eclipse.equinox.launcher_1.8.0.jar"))
        assertNull(JdtlsLaunch.home(home.resolve("bin/jdtls")), "two launcher jars")
        assertNull(JdtlsLaunch.home(Path.of("jdtls")), "no folder above the file")
    }

    @Test
    fun `a folder named like a launcher is not a launcher jar`() {
        val executable = unpack()
        home.resolve("plugins/org.eclipse.equinox.launcher_1.9.0.jar").createDirectories()

        val arguments = checkNotNull(JdtlsLaunch.command(home, home, dir, 1024, null)).arguments

        assertEquals(home, JdtlsLaunch.home(executable))
        assertEquals(launcher.toString(), arguments[arguments.indexOf("-jar") + 1])
    }

    @Test
    fun `home is null when the folder has no config for this OS`() {
        val executable = unpack()
        val config = JdtlsLaunch.configFolder(home) ?: return // not a platform Thoreau runs on
        Files.delete(config)

        assertNull(JdtlsLaunch.home(executable))
    }

    @ParameterizedTest
    @CsvSource(
        "Linux,amd64,config_linux",
        "Linux,x86_64,config_linux",
        "Linux,aarch64,config_linux_arm",
        "Linux,arm64,config_linux_arm",
        "Mac OS X,aarch64,config_mac_arm",
        "Mac OS X,arm64,config_mac_arm",
        "Mac OS X,x86_64,config_mac",
        "Mac OS X,amd64,config_mac",
    )
    fun `the config folder follows the OS and the architecture`(
        os: String,
        arch: String,
        expected: String,
    ) {
        unpack()

        assertEquals(home.resolve(expected), JdtlsLaunch.configFolder(home, os, arch))
    }

    @ParameterizedTest
    @CsvSource("Windows 11,amd64", "Linux,riscv64", "FreeBSD,amd64", "Linux,''")
    fun `there is no config folder for another OS or architecture`(
        os: String,
        arch: String,
    ) {
        unpack()

        assertNull(JdtlsLaunch.configFolder(home, os, arch))
    }

    @Test
    fun `the config folder is null when it is missing`() {
        unpack()
        Files.delete(home.resolve("config_linux_arm"))

        assertNull(JdtlsLaunch.configFolder(home, "Linux", "aarch64"))
    }

    @Test
    fun `the command runs Thoreau's java with the jdtls options and the data folder as working directory`() {
        unpack()
        val java = dir.resolve("jdk")
        val config = home.resolve("config_linux")
        val data = dir.resolve("data")

        val command = JdtlsLaunch.command(home, config, data, 3072, null, java)

        assertEquals(
            ServerCommand(
                java.resolve("bin/java"),
                listOf(
                    "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                    "-Dosgi.bundles.defaultStartLevel=4",
                    "-Declipse.product=org.eclipse.jdt.ls.core.product",
                    "-Djava.import.generatesMetadataFilesAtProjectRoot=false",
                    "-Xmx3072m",
                    "--add-modules=ALL-SYSTEM",
                    "--add-opens",
                    "java.base/java.util=ALL-UNNAMED",
                    "--add-opens",
                    "java.base/java.lang=ALL-UNNAMED",
                    "-jar",
                    launcher.toString(),
                    "-configuration",
                    config.toString(),
                    "-data",
                    data.toString(),
                ),
                workingDirectory = data,
            ),
            command,
        )
    }

    @Test
    fun `the command gets a javaagent for Lombok only with a jar`() {
        unpack()
        val lombok = dir.resolve("lombok-1.18.48.jar")
        val config = home.resolve("config_linux")

        val with = checkNotNull(JdtlsLaunch.command(home, config, dir.resolve("d"), 2048, lombok)).arguments
        val without = checkNotNull(JdtlsLaunch.command(home, config, dir.resolve("d"), 2048, null)).arguments

        assertEquals("-javaagent:$lombok", with[with.indexOf("-jar") - 1])
        assertEquals(with - "-javaagent:$lombok", without)
    }

    @Test
    fun `the command is null for a folder that is not an unpacked jdtls`() {
        unpack(launchers = emptyList())

        assertNull(JdtlsLaunch.command(home, home.resolve("config_linux"), dir.resolve("d"), 2048, null))
    }
}
