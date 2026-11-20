package dev.sluide.jshell.engine

import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

private val log = System.getLogger(ProtocolReader::class.java.name)

/**
 * The agent's messages from its standard output, one JSON object per line. A line that is no JSON object is logged
 * and skipped. `output` messages of one evaluation (`id`) and stream (`error`) that are already buffered when the
 * first is read come joined into one, up to [outputCap] characters: 100,000 printlns arrive as 100,000 messages within
 * half a second.
 */
internal class ProtocolReader(
    input: InputStream,
    private val outputCap: Int = 64 * 1024,
) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val mapper = JsonMapper.builder().build()

    /** A message read while joining outputs that belongs to the next call. */
    private var pending: ObjectNode? = null

    /** The next message, null at the end of the stream. */
    fun next(): ObjectNode? {
        val first = pending ?: read() ?: return null
        pending = null
        if (!isOutput(first)) return first
        val text = StringBuilder(first.path("text").asString())
        while (reader.ready()) {
            val following = read() ?: break
            val joins =
                isOutput(following) &&
                    following.get("id") == first.get("id") &&
                    following.path("error") == first.path("error") &&
                    text.length + following.path("text").asString().length <= outputCap
            if (!joins) {
                pending = following
                break
            }
            text.append(following.path("text").asString())
        }
        return first.put("text", text.toString())
    }

    private fun isOutput(message: ObjectNode) = message.path("event").asString() == "output"

    /** The next line that is a JSON object; null at the end. */
    private fun read(): ObjectNode? {
        while (true) {
            val line = reader.readLine() ?: return null
            val message =
                try {
                    mapper.readTree(line)
                } catch (_: JacksonException) {
                    null
                }
            if (message is ObjectNode) return message
            // the line itself may be the user's output: only its length is logged
            log.log(
                System.Logger.Level.WARNING,
                "The JShell agent sent a line that is no JSON object (${line.length} characters)",
            )
        }
    }
}
