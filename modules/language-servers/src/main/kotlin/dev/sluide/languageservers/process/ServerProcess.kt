package dev.sluide.languageservers.process

import dev.sluide.languageservers.ServerCommand
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** A running language server: its LSP stream and its end. Tests replace it with an in-process fake. */
internal interface ServerProcess {
    /** Name in log lines and thread names: `python#3`. */
    val name: String

    /** The server's standard output: the LSP stream it sends. */
    val input: InputStream

    /** The server's standard input: the LSP stream it reads. */
    val output: OutputStream

    /** Blocks until the server ended; its exit code. */
    fun waitFor(): Int

    /** Waits at most [millis] for the end; whether it ended. */
    fun waitFor(millis: Long): Boolean

    /** Kills the server and every process it started, at once. */
    fun destroyTree()
}

/** Starts a server process for a command in a project folder. */
internal fun interface ServerStarter {
    /** @throws IOException if the process cannot be started. */
    fun start(
        name: String,
        command: ServerCommand,
        root: Path,
    ): ServerProcess
}
