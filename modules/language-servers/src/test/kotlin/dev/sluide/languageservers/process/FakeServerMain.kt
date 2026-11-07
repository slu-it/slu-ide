package dev.sluide.languageservers.process

import dev.sluide.languageservers.FakeServer
import org.eclipse.lsp4j.launch.LSPLauncher
import kotlin.system.exitProcess

/**
 * A language server process for [OsServerStarterTest]: the in-process fake on standard input and output, with a child
 * process (`sleep`, on Linux and macOS) that it does not end itself.
 */
fun main() {
    ProcessBuilder("sleep", "600").start()
    val server = FakeServer()
    server.onExit = { exitProcess(0) }
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    System.err.println("fake server ready")
    launcher.startListening().get()
}
