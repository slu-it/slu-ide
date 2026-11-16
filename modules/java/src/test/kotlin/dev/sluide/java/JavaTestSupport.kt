package dev.sluide.java

import com.google.gson.JsonElement
import dev.sluide.languageservers.SessionAccess
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A [SessionAccess] that records what the extension sends. */
internal class RecordingAccess : SessionAccess {
    val notifications = CopyOnWriteArrayList<Pair<String, Any?>>()
    val configurations = CopyOnWriteArrayList<Any>()
    val restarts = CopyOnWriteArrayList<Unit>()

    override fun executeCommand(
        command: String,
        args: List<Any?>,
    ): CompletableFuture<Any?> = CompletableFuture.completedFuture(null)

    override fun request(
        method: String,
        params: Any?,
    ): CompletableFuture<JsonElement?> = CompletableFuture.completedFuture(null)

    override fun notify(
        method: String,
        params: Any?,
    ) {
        notifications += method to params
    }

    override fun configurationChanged(settings: Any) {
        configurations += settings
    }

    override fun keptDiagnostics(file: Path): List<Diagnostic> = emptyList()

    override fun refreshFolder(folder: Path) {}

    override fun workspaceChanged() {}

    override fun restart() {
        restarts += Unit
    }
}

/** An executor that runs every task on the calling thread, for deterministic session tests. */
internal class InlineExecutor : AbstractExecutorService() {
    @Volatile var down = false

    // Like a real executor after shutdown: a task handed to it is refused.
    override fun execute(command: Runnable) {
        if (down) throw RejectedExecutionException("shut down")
        command.run()
    }

    override fun shutdown() {
        down = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        down = true
        return mutableListOf()
    }

    override fun isShutdown() = down

    override fun isTerminated() = down

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = down
}

/** A folder that Jdks reads as a JDK of [major]. */
internal fun fakeJdk(
    home: Path,
    major: Int,
): Path {
    home.resolve("bin").createDirectories()
    home.resolve("bin/java").writeText("#!/bin/sh\n")
    home.resolve("release").writeText("JAVA_VERSION=\"$major.0.1\"\n")
    return home
}

/** An unpacked jdtls: a launcher jar, every `config_*` folder, `bin/jdtls`; returns the `bin/jdtls` marker. */
internal fun fakeJdtlsHome(home: Path): Path {
    home.resolve("plugins").createDirectories()
    home.resolve("plugins/org.eclipse.equinox.launcher_1.7.0.jar").writeText("jar")
    for (config in listOf("config_linux", "config_linux_arm", "config_mac", "config_mac_arm")) {
        home.resolve(config).createDirectories()
        home.resolve(config).resolve("config.ini").writeText("osgi.bundles=x")
    }
    val marker = home.resolve("bin/jdtls")
    marker.parent.createDirectories()
    marker.writeText("#!/bin/sh\n")
    marker.toFile().setExecutable(true)
    return marker
}

/** Waits until [condition] holds, at most 5 s (the signal is the condition itself, not a fixed delay). */
internal fun awaitCondition(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** A file with [text], its folders created. */
internal fun Path.file(text: String = ""): Path {
    parent.createDirectories()
    Files.writeString(this, text)
    return this
}
