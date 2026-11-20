package dev.sluide.jshell

import dev.sluide.jshell.agent.Json

/** One line of a recording under `src/test/resources/jshell/`: a message to the agent (`in`) or from it (`out`). */
internal data class RecordedLine(
    val number: Int,
    val incoming: Boolean,
    val message: Map<String, Any?>,
)

/** The agent recordings (their README says what each shows); messages parsed with the agent's own codec. */
internal object Recordings {
    fun load(name: String): List<RecordedLine> {
        val text =
            Recordings::class.java
                .getResourceAsStream(
                    "/jshell/$name.jsonl",
                )?.use { String(it.readAllBytes(), Charsets.UTF_8) }
                ?: error("no recording $name")
        return text.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
            val entry = Json.parse(line)

            @Suppress("UNCHECKED_CAST")
            RecordedLine(index + 1, entry["dir"] == "in", entry["msg"] as Map<String, Any?>)
        }
    }
}
