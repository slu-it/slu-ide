package dev.sluide.ide

import dev.sluide.settings.ThemedWindows
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Spring configuration; component scanning covers all Thoreau modules below `dev.sluide`. */
@SpringBootApplication(scanBasePackages = ["dev.sluide"])
class SluIdeConfiguration

/**
 * JavaFX application that owns the lifecycle of the Spring context. Folders given on the command line
 * (`Thoreau [folder…]`) are opened at startup: the first in the startup window, every other one in a new window.
 * Without folders the project picker is shown instead (ADR 0034). The application ends when the picker is cancelled
 * while no window is open, not when the last window closes.
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
        // Only now: until Spring has configured logging, the JVM's own print to stderr is the better place. Also gets
        // the exceptions of JavaFX event handlers, which the toolkit hands to the thread's handler (ADR 0051).
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> logUncaught(thread, error) }
    }

    override fun start(primaryStage: Stage) {
        context.getBean(SystemBrowser::class.java).hostServices = hostServices
        val folders = startupFolders(parameters.unnamed)
        // The project picker replaces quitting when the last window is closed (ADR 0034).
        Platform.setImplicitExit(false)
        // The picker may be the first window, so the theme must apply before any main window installs it.
        context.getBean(ThemedWindows::class.java).install()
        val windows = context.getBean(ProjectWindows::class.java)
        windows.start(primaryStage, folders)
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
