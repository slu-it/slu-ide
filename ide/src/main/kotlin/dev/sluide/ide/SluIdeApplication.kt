package dev.sluide.ide

import javafx.application.Application
import javafx.stage.Stage
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Spring configuration; component scanning covers all SluIde modules below `dev.sluide`. */
@SpringBootApplication(scanBasePackages = ["dev.sluide"])
class SluIdeConfiguration

/**
 * JavaFX application that owns the lifecycle of the Spring context. Folders given on the command line
 * (`Thoreau [folder…]`) are opened at startup: the first in the startup window, every other one in a new window.
 */
class SluIdeApplication : Application() {
    private lateinit var context: ConfigurableApplicationContext

    override fun init() {
        context =
            SpringApplicationBuilder(SluIdeConfiguration::class.java)
                .web(WebApplicationType.NONE)
                .headless(false)
                // The command-line arguments are folders (startupFolders), not Spring properties.
                .run()
    }

    override fun start(primaryStage: Stage) {
        context.getBean(SystemBrowser::class.java).hostServices = hostServices
        val folders = startupFolders(parameters.unnamed)
        val windows = context.getBean(ProjectWindows::class.java)
        windows.show(primaryStage, folders.firstOrNull())
        folders.drop(1).forEach(windows::open)
    }

    override fun stop() {
        context.close()
    }
}

/**
 * The command-line [args] that are folders, resolved against the working directory. Every other argument is passed
 * to [warn] and skipped, so a typo does not stop the IDE from starting.
 */
internal fun startupFolders(
    args: List<String>,
    warn: (String) -> Unit = System.err::println,
): List<Path> =
    args.mapNotNull { arg ->
        val path =
            try {
                Path.of(arg)
            } catch (_: InvalidPathException) {
                null
            }
        if (path != null && Files.isDirectory(path)) {
            path.toAbsolutePath().normalize()
        } else {
            warn("Thoreau: not a folder, ignored: $arg")
            null
        }
    }
