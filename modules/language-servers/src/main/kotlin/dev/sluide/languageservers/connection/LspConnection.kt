package dev.sluide.languageservers.connection

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.ServerFeatures
import dev.sluide.languageservers.process.ServerProcess
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.TimeUnit

private val log = System.getLogger(LspConnection::class.java.name)

/**
 * lsp4j over one server process: [server] is the proxy that sends requests and notifications, [client] answers what
 * the server sends, read on a thread of its own (`sluide-lsp-<name>-reader`).
 */
internal class LspConnection(
    val process: ServerProcess,
    client: LanguageClient,
    /** What this server offers; filled from `initialize` and its registrations. */
    val features: ServerFeatures,
) {
    private val reader = NamedThreads.single("sluide-lsp-${process.name}-reader")
    private val launcher =
        Launcher
            .Builder<LanguageServer>()
            .setLocalService(client)
            .setRemoteInterface(LanguageServer::class.java)
            .setInput(process.input)
            .setOutput(process.output)
            .setExecutorService(reader)
            .create()

    /** Sends to the server; writes on the calling thread (lsp4j serializes writes). */
    val server: LanguageServer = launcher.remoteProxy

    /** Starts reading what the server sends. */
    fun listen() {
        launcher.startListening()
    }

    /**
     * Ends the server politely: `shutdown`, at most [shutdownMillis] for its answer, then `exit`, and kills it with its
     * descendants if it has not ended [exitMillis] later. Blocks; never on the FX thread.
     */
    fun shutdown(
        shutdownMillis: Long,
        exitMillis: Long,
    ) {
        // On a thread of its own: a write can block while the server reads nothing, and only killing it ends that.
        val polite =
            NamedThreads.virtual("sluide-lsp-${process.name}-shutdown") {
                try {
                    server.shutdown().get(shutdownMillis, TimeUnit.MILLISECONDS)
                    server.exit()
                } catch (e: Exception) {
                    log.log(System.Logger.Level.INFO, "lsp ${process.name} shutdown not answered: $e")
                }
            }
        polite.join(shutdownMillis + exitMillis)
        if (!process.waitFor(exitMillis)) {
            log.log(System.Logger.Level.INFO, "lsp ${process.name} did not exit after shutdown, killed")
            process.destroyTree()
        }
        polite.interrupt()
        reader.shutdownNow()
    }

    /** Kills the server with its descendants at once. */
    fun kill() {
        process.destroyTree()
        reader.shutdownNow()
    }
}
