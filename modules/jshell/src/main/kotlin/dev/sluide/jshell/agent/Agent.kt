@file:JvmName("Agent")

package dev.sluide.jshell.agent

import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * The agent process (ADR 0191): `<jdk>/bin/java -cp agent.jar:kotlin-stdlib.jar dev.sluide.jshell.agent.Agent`.
 * Requests on standard input, one JSON object per line; messages on standard output, the same way. It ends after
 * `close`, at the end of its input (Thoreau closed it or died) and after the execution JVM ended.
 */
fun main() {
    val protocol = FileOutputStream(FileDescriptor.out)
    // Anything the agent or a library prints by mistake must not reach the protocol stream.
    System.setOut(System.err)
    val server =
        AgentServer(
            send = { message ->
                val line = (Json.write(message) + "\n").toByteArray(Charsets.UTF_8)
                synchronized(protocol) {
                    protocol.write(line)
                    protocol.flush()
                }
            },
            exit = { System.exit(it) },
        )
    server.start()
    val input = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    server.serve(input::readLine)
    System.exit(0)
}
