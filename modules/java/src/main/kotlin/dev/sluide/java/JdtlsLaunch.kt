package dev.sluide.java

import dev.sluide.languageservers.ServerCommand
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private const val LAUNCHER_PREFIX = "org.eclipse.equinox.launcher_"

/** How jdtls is started from an unpacked archive (ADR 0179); the per-window command is built from these. */
object JdtlsLaunch {
    /**
     * jdtls's folder for its marker [executable] (`<folder>/bin/jdtls`), or null if it is not an unpacked jdtls (no
     * single launcher jar, no config for this OS). Cheap; any thread; never throws.
     */
    fun home(executable: Path): Path? {
        val home = executable.parent?.parent ?: return null
        return home.takeIf { launcherJar(it) != null && configFolder(it) != null }
    }

    /**
     * `config_linux`, `config_linux_arm`, `config_mac` or `config_mac_arm` of [home] for this OS and architecture;
     * null if missing.
     */
    fun configFolder(
        home: Path,
        os: String = System.getProperty("os.name"),
        arch: String = System.getProperty("os.arch"),
    ): Path? {
        val arm =
            when (arch.lowercase(Locale.ROOT)) {
                "aarch64", "arm64" -> true
                "amd64", "x86_64" -> false
                else -> return null
            }
        val name =
            when {
                os.lowercase(Locale.ROOT).startsWith("linux") -> if (arm) "config_linux_arm" else "config_linux"
                os.lowercase(Locale.ROOT).startsWith("mac") -> if (arm) "config_mac_arm" else "config_mac"
                else -> return null
            }
        return home.resolve(name).takeIf { Files.isDirectory(it) }
    }

    /**
     * The command: Thoreau's own `java` (`javaHome`/bin/java) with the options of jdtls's own launcher script, the
     * `-Xmx` of [maxMemoryMb], the Lombok agent if [lombok] is given, the launcher jar of [home], [config] and [data];
     * working directory [data]. Null if [home] is not usable.
     */
    fun command(
        home: Path,
        config: Path,
        data: Path,
        maxMemoryMb: Int,
        lombok: Path?,
        javaHome: Path = Path.of(System.getProperty("java.home")),
    ): ServerCommand? {
        val launcher = launcherJar(home) ?: return null
        val arguments =
            listOf(
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Djava.import.generatesMetadataFilesAtProjectRoot=false",
                "-Xmx${maxMemoryMb}m",
                "--add-modules=ALL-SYSTEM",
                "--add-opens",
                "java.base/java.util=ALL-UNNAMED",
                "--add-opens",
                "java.base/java.lang=ALL-UNNAMED",
            ) +
                listOfNotNull(lombok?.let { "-javaagent:$it" }) +
                listOf("-jar", launcher.toString(), "-configuration", config.toString(), "-data", data.toString())
        return ServerCommand(javaHome.resolve("bin").resolve("java"), arguments, workingDirectory = data)
    }

    /** The one `plugins/org.eclipse.equinox.launcher_<version>.jar` of [home]; null for none or several. */
    private fun launcherJar(home: Path): Path? =
        try {
            Files.newDirectoryStream(home.resolve("plugins"), "$LAUNCHER_PREFIX*.jar").use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList().singleOrNull()
            }
        } catch (_: IOException) {
            null
        } catch (_: DirectoryIteratorException) {
            null
        }
}
