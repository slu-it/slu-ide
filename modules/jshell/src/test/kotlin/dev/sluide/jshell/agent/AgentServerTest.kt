package dev.sluide.jshell.agent

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the recordings do not show, against the real JShell of the build's JDK (AgentRecordingsTest has the rest). */
class AgentServerTest {
    private fun evaluate(
        id: Int,
        text: String,
        start: Int = 0,
        end: Int = start,
    ) = mapOf("id" to id, "op" to "evaluate", "text" to text, "rangeStart" to start, "rangeEnd" to end)

    /** The messages of evaluation [id] up to its `finished`. */
    private fun AgentHarness.until(id: Int): List<Map<String, Any?>> {
        val messages = mutableListOf<Map<String, Any?>>()
        while (true) {
            val message = next()
            assertEquals(id.toLong(), message["id"], "a message of another request: $message")
            messages += message
            if (message["event"] == "finished") return messages
        }
    }

    private fun ready(agent: AgentHarness) = assertEquals("ready", agent.next()["event"])

    @Test
    fun `a line that is no JSON object is answered with an error and the agent serves on`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.sendLine("not json")
            assertEquals(mapOf("event" to "error", "message" to "bad request: an object expected"), agent.next())
            agent.sendLine("   ")
            agent.send(evaluate(1, "int x = 1;"))
            assertEquals("snippetStarted", agent.next()["event"])
        }
    }

    @Test
    fun `a request without its fields is answered with an error under its id`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(mapOf("id" to 1, "op" to "evaluate", "text" to "1", "rangeStart" to 0))
            agent.send(mapOf("id" to 2, "op" to "evaluate", "rangeStart" to 0, "rangeEnd" to 0))
            agent.send(mapOf("id" to 3, "op" to "complete", "text" to "x", "caret" to 5))
            agent.send(mapOf("id" to 4, "op" to "complete", "text" to "x", "caret" to -1))
            agent.send(mapOf("id" to 5, "op" to "complete", "caret" to 0))
            agent.send(mapOf("id" to 6, "op" to "nope"))
            agent.send(mapOf("id" to 7, "op" to "evaluate", "text" to "1", "rangeEnd" to 0))
            agent.send(mapOf("id" to 8, "op" to "evaluate", "text" to 1, "rangeStart" to 0, "rangeEnd" to 0))
            agent.send(mapOf("id" to 9, "op" to "evaluate", "text" to "1", "rangeStart" to "0", "rangeEnd" to 0))
            agent.send(mapOf("id" to 10, "op" to "evaluate", "text" to "1", "rangeStart" to 0, "rangeEnd" to "0"))
            agent.send(mapOf("id" to 11, "op" to "complete", "text" to "x"))
            agent.send(mapOf("id" to 12, "op" to "complete", "text" to 1, "caret" to 0))
            agent.send(mapOf("id" to 13, "op" to "complete", "text" to "x", "caret" to "0"))
            val evaluateError = "bad request: evaluate needs text, rangeStart and rangeEnd"
            val completeError = "bad request: complete needs text and a caret within it"
            assertEquals(
                listOf(
                    mapOf("id" to 1L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 2L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 3L, "event" to "error", "message" to completeError),
                    mapOf("id" to 4L, "event" to "error", "message" to completeError),
                    mapOf("id" to 5L, "event" to "error", "message" to completeError),
                    mapOf("id" to 6L, "event" to "error", "message" to "unknown op nope"),
                    mapOf("id" to 7L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 8L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 9L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 10L, "event" to "error", "message" to evaluateError),
                    mapOf("id" to 11L, "event" to "error", "message" to completeError),
                    mapOf("id" to 12L, "event" to "error", "message" to completeError),
                    mapOf("id" to 13L, "event" to "error", "message" to completeError),
                ),
                List(13) { agent.next() },
            )
        }
    }

    @Test
    fun `a caret at either end of the text completes`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(mapOf("id" to 1, "op" to "complete", "text" to "Math.ab", "caret" to 7))
            val answer = agent.next()
            assertEquals("completions", answer["event"])
            assertEquals(5L, answer["anchor"])
            agent.send(mapOf("id" to 2, "op" to "complete", "text" to "Math.ab", "caret" to 0))
            assertEquals("completions", agent.next()["event"])
        }
    }

    @Test
    fun `stop while nothing runs sends nothing`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(mapOf("op" to "stop"))
            agent.send(evaluate(1, "int x = 1;"))
            assertEquals(
                mapOf("id" to 1L, "event" to "snippetStarted", "start" to 0L, "end" to 10L, "line" to 0L),
                agent.next(),
            )
        }
    }

    @Test
    fun `a stop that comes while the snippet still compiles stops it`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, "while (true) {}"))
            assertEquals("snippetStarted", agent.next()["event"])
            // At once: JShell compiles the snippet first, and a stop before it runs is lost unless sent again.
            agent.send(mapOf("op" to "stop"))
            assertEquals(listOf("stopped", "finished"), agent.until(1).map { it["event"] })
        }
    }

    @Test
    fun `a stop sent with the request, before the snippet runs, still stops it`() {
        AgentHarness().use { agent ->
            ready(agent)
            // no wait for snippetStarted: the first stop comes before JShell runs anything and must be sent again
            agent.send(evaluate(1, "while (true) {}"))
            agent.send(mapOf("op" to "stop"))
            assertEquals(listOf("snippetStarted", "stopped", "finished"), agent.until(1).map { it["event"] })
        }
    }

    @Test
    fun `a stop ends a run of several snippets after the stopped one`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text = "while (true) {}\nint after = 1;"
            agent.send(evaluate(1, text, 0, text.length))
            assertEquals("snippetStarted", agent.next()["event"])
            agent.send(mapOf("op" to "stop"))
            assertEquals(listOf("stopped", "finished"), agent.until(1).map { it["event"] })
        }
    }

    @Test
    fun `declarations get JShell's feedback`() {
        val text =
            """
            interface I {}
            enum E { A }
            record R(int x) {}
            @interface Note {}
            void g() { h(); }
            class L extends M {}
            void g() {}
            int g() { return 1; }
            g()
            import java.util.List;
            int unset;
            void bad() { int x = "s"; }
            void bad() {}
            int u = undefinedVar;
            Undeclared w;
            public record P(int x) {}
            /* c */ record Q(int x) {}
            @Deprecated record S() {}
            """.trimIndent()
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, text, 0, text.length))
            assertEquals(
                listOf(
                    "created interface I",
                    "created enum E",
                    "created record R",
                    "created annotation interface Note",
                    "created method g(), however, it cannot be invoked until method h() is declared",
                    "created class L, however, it cannot be referenced until class M is declared",
                    "modified method g()",
                    "replaced method g()",
                    "\$1 ==> 1",
                    "unset ==> 0",
                    "created method bad()",
                    "created record P",
                    "created record Q",
                    "created record S",
                ),
                agent.until(1).filter { it["event"] == "value" }.map { it["feedback"] },
            )
        }
    }

    @Test
    fun `an exception from the JDK lists its frames with their files`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, "\n\nInteger.parseInt(\"x\")"))
            val thrown = agent.until(1).single { it["event"] == "thrown" }
            val text = thrown["text"] as String
            assertTrue(text.startsWith("java.lang.NumberFormatException: For input string: \"x\"\n"), text)
            assertTrue(text.contains("\n\tat java.lang.Integer.parseInt (Integer.java:"), text)
            assertTrue(text.endsWith("\n\tat (line 3)"), text)
            assertEquals(2L, thrown["line"])
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u00A0\nint b = 2;\n", "int a = 1;\n\u00A0\nint b = 2;\n"])
    fun `a line of no-break spaces does not end the run`(doc: String) {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, doc, 0, doc.length))
            val events =
                generateSequence { agent.next() }
                    .takeWhile {
                        it["event"] != "finished" &&
                            it["event"] != "error"
                    }.toList()
            assertTrue(events.any { it["feedback"] == "b ==> 2" }, events.toString())
        }
    }

    @Test
    fun `stack lines count from the comment before a snippet's code`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text = "/* helper\n */ int z() {\n  return 1 / 0;\n}\nz()\n"
            agent.send(evaluate(1, text, 0, text.length))
            val thrown = agent.until(1).single { it["event"] == "thrown" }
            assertTrue((thrown["text"] as String).contains("\n\tat z (line 3)\n"), thrown.toString())
            assertEquals(2L, thrown["line"])
        }
    }

    @Test
    fun `an exception without a message shows its class only`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, "throw new RuntimeException();"))
            val thrown = agent.until(1).single { it["event"] == "thrown" }
            assertEquals("java.lang.RuntimeException\n\tat (line 1)", thrown["text"])
        }
    }

    @Test
    fun `only standard error carries the exit report, and output after it keeps the status`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text =
                "System.out.print(\"\\u0000sluide-exit Runtime.exit() called with status: 7\\n\");\n" +
                    "Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(\"bye\")));\n" +
                    "System.exit(3);"
            agent.send(evaluate(1, text, 0, text.length))
            val events = agent.until(1)
            assertEquals(
                listOf("\u0000sluide-exit Runtime.exit() called with status: 7\n"),
                events.filter { it["event"] == "output" && it["error"] == false }.map { it["text"] },
            )
            assertEquals(mapOf("event" to "ended", "reason" to "exit", "exitStatus" to 3L), agent.next())
        }
    }

    @Test
    fun `calling a method with an undeclared reference names it`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text = "void p() { q(); }\np()"
            agent.send(evaluate(1, text, 0, text.length))
            val thrown = agent.until(1).single { it["event"] == "thrown" }
            assertEquals(
                "Attempted to call p which cannot be invoked until its references are declared",
                (thrown["text"] as String).lines().first(),
            )
        }
    }

    @Test
    fun `an exit after the evaluation ended the session at once`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text =
                // a snippet's text, no sleep of the test
                "new Thread(() -> { try { Thread.sleep(300); } catch (Exception e) {} " +
                    "System.exit(4); }).start();"
            agent.send(evaluate(1, text))
            agent.until(1)
            // the status may come after the end was noticed: an exit outside an evaluation can end without it
            val ended = agent.next()
            assertEquals(listOf("ended", "exit"), listOf(ended["event"], ended["reason"]))
        }
    }

    @Test
    fun `completion works on the snippet at the caret only`() {
        AgentHarness().use { agent ->
            ready(agent)
            // the first line lacks its ';'; the anchor is a document offset
            agent.send(mapOf("id" to 1, "op" to "complete", "text" to "int n = 3\nMath.ma", "caret" to 17))
            val answer = agent.next()
            assertEquals(15L, answer["anchor"])
            assertTrue(
                (answer["items"] as List<*>).contains(mapOf("text" to "max(", "smart" to true)),
                answer.toString(),
            )
        }
    }

    @Test
    fun `an exit ends the run before the snippets after it`() {
        AgentHarness().use { agent ->
            ready(agent)
            val text = "System.exit(3); int y = 1;"
            agent.send(evaluate(1, text, 0, text.length))
            assertEquals(listOf("snippetStarted", "finished"), agent.until(1).map { it["event"] })
            assertEquals(mapOf("event" to "ended", "reason" to "exit", "exitStatus" to 3L), agent.next())
        }
    }

    @Test
    fun `a stop sent again after its evaluation ended leaves the next one alone`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, "while (true) {}"))
            assertEquals("snippetStarted", agent.next()["event"])
            agent.send(mapOf("op" to "stop"))
            assertEquals(listOf("stopped", "finished"), agent.until(1).map { it["event"] })
            // a snippet's text, no sleep of the test; a stopped block would leave done at 0
            val text = "int done = 0;\n{ Thread.sleep(1000); done = 1; }\ndone"
            agent.send(evaluate(2, text, 0, text.length))
            val events = agent.until(2)
            assertEquals(
                listOf("done ==> 0", "done ==> 1"),
                events.filter { it["event"] == "value" }.map { it["feedback"] },
            )
        }
    }

    @Test
    fun `a message that cannot be sent ends its request with an error`() {
        AgentHarness(failing = { it["event"] == "snippetStarted" || it["event"] == "completions" }).use { agent ->
            ready(agent)
            agent.send(evaluate(1, "int x = 1;"))
            assertEquals(
                mapOf(
                    "id" to 1L,
                    "event" to "error",
                    "message" to "java.lang.IllegalStateException: send failed",
                ),
                agent.next(),
            )
            agent.send(evaluate(2, "int y = 2;"))
            assertEquals("finished", agent.until(2).last()["event"])
        }
        AgentHarness(failing = { it["event"] == "completions" }).use { agent ->
            ready(agent)
            agent.send(mapOf("id" to 1, "op" to "complete", "text" to "Math.ab", "caret" to 7))
            assertEquals(
                mapOf(
                    "id" to 1L,
                    "event" to "error",
                    "message" to "java.lang.IllegalStateException: send failed",
                ),
                agent.next(),
            )
        }
    }

    @Test
    fun `a request after the execution JVM ended is refused`() {
        AgentHarness().use { agent ->
            ready(agent)
            agent.send(evaluate(1, "System.exit(0);"))
            agent.until(1)
            assertEquals(mapOf("event" to "ended", "reason" to "exit", "exitStatus" to 0L), agent.next())
            assertEquals(0, agent.exits.poll(10, TimeUnit.SECONDS))
            agent.send(evaluate(2, "1"))
            assertEquals(mapOf("id" to 2L, "event" to "error", "message" to "ended"), agent.next())
        }
    }

    @Test
    fun `the end of the input ends the execution JVM`() {
        val before =
            ProcessHandle
                .current()
                .children()
                .toList()
                .toSet()
        val agent = AgentHarness()
        ready(agent)
        val execution =
            ProcessHandle
                .current()
                .children()
                .toList()
                .filter { it !in before && it.isAlive }
        assertEquals(1, execution.size, "the execution JVM")
        agent.close()
        assertEquals(mapOf("event" to "ended", "reason" to "closed"), agent.left().single())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (execution.single().isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(execution.single().isAlive)
    }
}
