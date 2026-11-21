package dev.sluide.jshell.agent

import dev.sluide.jshell.Recordings
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * The agent's server answers each recording's requests with the recording's messages, in order and count: the
 * recordings were made with the spike's agent process on JDK 25 (README), this runs the module's agent on the build's
 * JDK. `ready` names this JVM's version and home.
 */
class AgentRecordingsTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "value", "print-without-newline", "compile-error", "runtime-exception", "multi-snippet", "caret",
            "completion", "stdin", "unicode", "busy", "stop", "late-output", "system-exit", "stdin-eof",
            "thrown-no-line",
            "halt", "complete-and-run", "complete-and-run-all",
        ],
    )
    fun `the agent answers a recording's requests as recorded`(name: String) {
        val lines = Recordings.load(name)
        val closes = lines.any { it.incoming && it.message["op"] == "close" }
        AgentHarness().use { agent ->
            for (line in lines) {
                if (line.incoming) {
                    agent.send(line.message)
                    continue
                }
                // stdin-eof.jsonl: the client closed the agent's input instead of sending `close`
                if (line.message["event"] == "ended" && line.message["reason"] == "closed" && !closes) agent.endInput()
                assertEquals(expected(line.message), agent.next(), "$name.jsonl line ${line.number}")
            }
            if (lines.last().message["reason"] == "exit") assertEquals(0, agent.exits.poll(10, TimeUnit.SECONDS))
            agent.close()
            assertEquals(emptyList(), agent.left(), "nothing after the recording's last message")
        }
    }

    // the older recordings' `agentPid` (null there) is no longer sent (README)
    private fun expected(message: Map<String, Any?>): Map<String, Any?> =
        if (message["event"] == "ready") {
            message - "agentPid" +
                mapOf(
                    "javaVersion" to System.getProperty("java.version"),
                    "javaHome" to System.getProperty("java.home"),
                )
        } else {
            message
        }
}
