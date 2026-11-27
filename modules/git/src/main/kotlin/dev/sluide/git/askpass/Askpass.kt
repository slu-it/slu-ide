package dev.sluide.git.askpass

import java.io.OutputStream
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.system.exitProcess

/**
 * The askpass helper ssh runs for a covered git call (ADR 0194; `SSH_ASKPASS`, through the wrapper script): it relays
 * ssh's prompt, its only argument, to Thoreau's server at `SLUIDE_ASKPASS_SOCKET` with the session's
 * `SLUIDE_ASKPASS_TOKEN`, and prints the answer and a line break for ssh (exit 0), or exits 1 for "no answer". It runs
 * in a JVM of its own and never logs: its standard error would reach git's message, and a prompt's answer is secret.
 */
object Askpass {
    @JvmStatic
    fun main(args: Array<String>): Unit = exitProcess(ask(args, System.getenv(), parentPid(), System.out))

    /** The pid of the process that started this one: ssh, as the wrapper script `exec`s java; -1 if unknown. */
    internal fun parentPid(): Long =
        ProcessHandle
            .current()
            .parent()
            .map { it.pid() }
            .orElse(-1L)

    /**
     * Relays the prompt in [args] with the variables of [environment], [parentPid] as ssh's pid, and writes the answer
     * to [output]; the exit value: 0 with an answer, 1 for none, for a missing argument or variable and for any error.
     */
    internal fun ask(
        args: Array<String>,
        environment: Map<String, String>,
        parentPid: Long,
        output: OutputStream,
    ): Int {
        val prompt = args.firstOrNull() ?: return 1
        val socket = environment["SLUIDE_ASKPASS_SOCKET"] ?: return 1
        val token = environment["SLUIDE_ASKPASS_TOKEN"] ?: return 1
        val variable = environment["SSH_ASKPASS_PROMPT"].orEmpty()
        val request = AskpassProtocol.encodeRequest(AskpassRequest(token, parentPid, variable, prompt)) ?: return 1
        return try {
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).use { channel ->
                val buffer = ByteBuffer.wrap(request)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.shutdownOutput()
                val reply = Channels.newInputStream(channel).readNBytes(AskpassProtocol.MAX_BYTES + 1)
                val answer = AskpassProtocol.decodeReply(reply) ?: return 1
                output.write(answer)
                output.write('\n'.code)
                output.flush()
                0
            }
        } catch (_: Exception) {
            1
        }
    }
}
