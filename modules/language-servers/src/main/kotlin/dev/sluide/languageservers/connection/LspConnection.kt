package dev.sluide.languageservers.connection

import com.google.gson.JsonElement
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.ServerFeatures
import dev.sluide.languageservers.process.ServerProcess
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.JsonRpcMethod
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val log = System.getLogger(LspConnection::class.java.name)

/**
 * The methods lsp4j knows from the interfaces, and every other one as taking and answering Gson's tree: lsp4j reads the
 * result of a request whose method it does not know as `null` (lsp4j 1.0.0, a session extension's `request`), and the
 * params of such notifications as the tree. Not `$/cancelRequest`: lsp4j reads its params as `CancelParams` only when
 * the map has no entry for it, else it logs that cancellation is disabled and cancels nothing.
 */
private class UntypedMethods(
    known: Map<String, JsonRpcMethod>,
) : HashMap<String, JsonRpcMethod>(known) {
    override fun get(key: String): JsonRpcMethod? =
        super.get(key) ?: if (key == MessageJsonHandler.CANCEL_METHOD.methodName) {
            null
        } else {
            JsonRpcMethod.request(key, JsonElement::class.java, JsonElement::class.java)
        }
}

/**
 * lsp4j over one server process: [server] is the proxy that sends requests and notifications, [client] answers what
 * the server sends, read on a thread of its own (`sluide-lsp-<name>-reader`).
 */
internal class LspConnection(
    val process: ServerProcess,
    client: LanguageClient,
    /** What this server offers; filled from `initialize` and its registrations. */
    val features: ServerFeatures,
    /** The message of the requests still waiting when it is stopped. */
    private val stoppedMessage: String,
    /**
     * Offered every notification the server sends, with its method and params as lsp4j read them, before [client]
     * gets it: `true` takes it (a method `LanguageClient` does not declare, which lsp4j would drop). Reader thread.
     */
    private val notifications: (method: String, params: Any?) -> Boolean = { _, _ -> false },
) {
    private val reader = NamedThreads.single("sluide-lsp-${process.name}-reader")
    private val launcher =
        object : Launcher.Builder<LanguageServer>() {
            override fun getSupportedMethods(): Map<String, JsonRpcMethod> = UntypedMethods(super.getSupportedMethods())
        }.setLocalService(client)
            .setRemoteInterface(LanguageServer::class.java)
            .setInput(process.input)
            .setOutput(process.output)
            .setExecutorService(reader)
            .wrapMessages(::routeNotifications)
            .create()

    /** Sends to the server; writes on the calling thread (lsp4j serializes writes). */
    val server: LanguageServer = launcher.remoteProxy

    /** Sends requests and notifications of any method to the server, as [server] does. */
    val endpoint: Endpoint = launcher.remoteEndpoint

    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    @Volatile
    private var abandoned: String? = null

    /**
     * Keeps [future], a request sent to this server, until it completes; [abandon] fails it. lsp4j leaves the
     * futures of a connection that ended incomplete.
     */
    fun track(future: CompletableFuture<*>) {
        inFlight.add(future)
        future.whenComplete { _, _ -> inFlight.remove(future) }
        abandoned?.let { message -> future.completeExceptionally(LanguageServerNotRunningException(message)) }
    }

    /** Fails every request still waiting for this server with a [LanguageServerNotRunningException]. */
    private fun abandon(message: String) {
        abandoned = message
        inFlight.toList().forEach { it.completeExceptionally(LanguageServerNotRunningException(message)) }
    }

    /**
     * Offers the notifications the server sends to [notifications] first. lsp4j wraps both the outgoing stream and the
     * incoming dispatch with this; only the incoming one is the [RemoteEndpoint].
     */
    private fun routeNotifications(consumer: MessageConsumer): MessageConsumer {
        if (consumer !is RemoteEndpoint) return consumer
        return MessageConsumer { message ->
            if (message !is NotificationMessage || !offered(message)) consumer.consume(message)
        }
    }

    private fun offered(message: NotificationMessage): Boolean =
        try {
            notifications(message.method, message.params)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.WARNING, "lsp ${process.name}: routing ${message.method} failed", e)
            false
        }

    /** Starts reading what the server sends. */
    fun listen() {
        launcher.startListening()
    }

    /**
     * Fails the requests still waiting, then ends the server politely: `shutdown`, at most [shutdownMillis] for its
     * answer, then `exit` (also without an answer), and kills it if it has not ended [exitMillis] later, waiting as
     * long again for it to be gone. The processes it had started when this began are killed in any case: once the
     * server has ended they are no longer its descendants. Blocks; never on the FX thread.
     */
    fun shutdown(
        shutdownMillis: Long,
        exitMillis: Long,
    ) {
        abandon(stoppedMessage)
        val descendants = process.descendants()
        // On a thread of its own: a write can block while the server reads nothing, and only killing it ends that.
        val polite =
            NamedThreads.virtual("sluide-lsp-${process.name}-shutdown") {
                try {
                    server.shutdown().get(shutdownMillis, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    log.log(System.Logger.Level.INFO, "lsp ${process.name} shutdown not answered: $e")
                }
                // A notification that cannot be written is logged by lsp4j and does not throw.
                server.exit()
            }
        polite.join(shutdownMillis + exitMillis)
        if (!process.waitFor(exitMillis)) {
            log.log(System.Logger.Level.INFO, "lsp ${process.name} did not exit after shutdown, killed")
            destroyAndWait(exitMillis)
        }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        polite.interrupt()
        reader.shutdownNow()
    }

    /** Kills the server with its descendants at once and waits at most [exitMillis] for it to be gone. Blocks. */
    fun kill(exitMillis: Long) {
        abandon(stoppedMessage)
        destroyAndWait(exitMillis)
        reader.shutdownNow()
    }

    /** Kills the server with its descendants and waits at most [exitMillis] for it to be gone: a kill takes a moment. */
    private fun destroyAndWait(exitMillis: Long) {
        process.destroyTree()
        if (!process.waitFor(exitMillis)) {
            log.log(System.Logger.Level.INFO, "lsp ${process.name} still runs $exitMillis ms after it was killed")
        }
    }
}
