package dev.sluide.jshell

import dev.sluide.jshell.EvalEvent.Diagnostic
import dev.sluide.jshell.EvalEvent.Finished
import dev.sluide.jshell.EvalEvent.Output
import dev.sluide.jshell.EvalEvent.SnippetStarted
import dev.sluide.jshell.EvalEvent.Stopped
import dev.sluide.jshell.EvalEvent.Thrown
import dev.sluide.jshell.EvalEvent.Value
import dev.sluide.testsupport.LogCapture
import dev.sluide.testsupport.collidingWith
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Evaluations and completions through the session, against the recorded agent (each test names its recording). */
class JShellSessionTest {
    private val fixture = SessionFixture()

    @Test
    fun `a value is reported between its snippet's start and the end`() {
        val agent = FakeAgent.of("value")
        fixture.start(agent).use { session ->
            assertEquals("25.0.5", session.javaVersion)
            assertTrue(session.alive)
            assertEquals(
                listOf(SnippetStarted(0, 10, 0), Value("x ==> 5"), Finished),
                fixture.run(session, "int x = 5;", 0),
            )
            assertFalse(session.running)
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `output without a newline and from both streams comes per snippet`() {
        val agent = FakeAgent.of("print-without-newline")
        fixture.start(agent).use { session ->
            assertEquals(
                listOf(SnippetStarted(0, 21, 0), Output("a", false), Finished),
                fixture.run(session, "System.out.print(\"a\")", 0),
            )
            val two = "System.err.print(\"e\"); System.out.println(\"b\")"
            assertEquals(
                listOf(
                    SnippetStarted(0, 22, 0),
                    Output("e", true),
                    SnippetStarted(23, 46, 0),
                    Output("b\n", false),
                    Finished,
                ),
                fixture.run(session, two, 0, two.length),
            )
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `compile errors come as diagnostics in document offsets`() {
        val agent = FakeAgent.of("compile-error")
        fixture.start(agent).use { session ->
            assertEquals(
                listOf(
                    SnippetStarted(9, 21, 1),
                    Diagnostic(17, 20, 1, "incompatible types: java.lang.String cannot be converted to int", true),
                    Finished,
                ),
                fixture.run(session, "// setup\nint y = \"s\";\n", 9),
            )
            val events = fixture.run(session, "String s = \"x\"\nint z = s.lenth();\n", 0, 34)
            assertEquals(
                Diagnostic(
                    23,
                    30,
                    1,
                    "cannot find symbol\n  symbol:   method lenth()\n  location: variable s of type java.lang.String",
                    true,
                ),
                events[3],
            )
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `an exception comes with its frames and the line it was thrown on`() {
        val agent = FakeAgent.of("runtime-exception")
        fixture.start(agent).use { session ->
            val text =
                "int f(int a) {\n  if (a > 1) throw new IllegalStateException(\"too big: \" + a);\n" +
                    "  return a;\n}\n\nf(5)\n"
            assertEquals(
                listOf(
                    SnippetStarted(0, 91, 0),
                    Value("created method f(int)"),
                    SnippetStarted(93, 97, 5),
                    Thrown("java.lang.IllegalStateException: too big: 5\n\tat f (line 2)\n\tat (line 6)", 1),
                    Finished,
                ),
                fixture.run(session, text, 0, text.length),
            )
            val cause = "throw new RuntimeException(\"boom\", new java.io.IOException(\"cause\"));"
            assertEquals(
                Thrown(
                    "java.lang.RuntimeException: boom\n\tat (line 1)\n" +
                        "Caused by: java.io.IOException: cause\n\tat (line 1)",
                    0,
                ),
                fixture.run(session, cause, 0)[1],
            )
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `a whole document runs snippet by snippet`() {
        val agent = FakeAgent.of("multi-snippet")
        val text =
            "// leading comment\nint a = 1;\n\n/* block */ int b = a + 1\n\nvoid f() {\n  System.out.println(b);\n}\n" +
                "   \nf();   // trailing comment\nString s = \"x\"; s.length()\nint q = \"no\";\nclass C {}\n" +
                "import java.util.*;\nint broken = ("
        fixture.start(agent).use { session ->
            val events = fixture.run(session, text, 0, text.length)
            assertEquals(10, events.count { it is SnippetStarted })
            assertEquals(Diagnostic(213, 213, 14, "reached end of file while parsing", true), events[events.size - 2])
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `the caret and the selection go to the agent as they are`() {
        val agent = FakeAgent.of("caret")
        val text = "int a = 1;\n\nint b = a + 1;\n\n\nSystem.out.println(a + b);\n"
        fixture.start(agent).use { session ->
            fixture.run(session, text, 0, 56)
            assertEquals(SnippetStarted(12, 26, 2), fixture.run(session, text, 22).first())
            assertEquals(SnippetStarted(12, 26, 2), fixture.run(session, text, 28).first())
            assertEquals(SnippetStarted(29, 55, 5), fixture.run(session, text, 56).first())
            assertEquals(2, fixture.run(session, text, 16, 40).count { it is SnippetStarted })
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `offsets count UTF-16 units`() {
        val agent = FakeAgent.of("unicode")
        fixture.start(agent).use { session ->
            // the recording's client sent 30, its Python length of the text
            assertEquals(
                listOf(
                    SnippetStarted(0, 27, 0),
                    Output("ü€😀\n", false),
                    SnippetStarted(28, 31, 0),
                    Value("\$1 ==> \"ü\""),
                    Finished,
                ),
                fixture.run(session, "System.out.println(\"ü€😀\"); \"ü\"", 0, 30),
            )
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `stop ends the running snippet and the session keeps its state, completion answers meanwhile`() {
        // stop.jsonl without lines 8 and 9: the session refuses a second evaluation itself
        val agent = FakeAgent.of("stop", only = (1..25) - setOf(8, 9))
        fixture.start(agent).use { session ->
            fixture.run(session, "int x = 7;", 0)
            val events = LinkedBlockingQueue<EvalEvent>()
            session.evaluate("while (true) {}", 0, 0) { events.put(it) }
            assertEquals(SnippetStarted(0, 15, 0), events.poll(10, TimeUnit.SECONDS))
            assertTrue(session.running)
            assertEquals(
                "A JShell evaluation is running",
                assertFailsWith<IllegalStateException> { session.evaluate("x", 0, 0) {} }.message,
            )
            val completions = session.complete("\"abc\".", 6).get(10, TimeUnit.SECONDS)
            assertEquals(6, completions.anchor)
            assertEquals(76, completions.items.size)
            assertEquals(JShellCompletion("charAt(", true), completions.items.first())
            session.stop()
            assertEquals(listOf(Stopped, Finished), fixture.until(events, Finished))
            assertFalse(session.running)
            assertEquals(Value("x ==> 7"), fixture.run(session, "x", 0)[1])
            // a snippet's text, no sleep of the test
            session.evaluate("Thread.sleep(60000)", 0, 0) { events.put(it) }
            assertEquals(SnippetStarted(0, 19, 0), events.poll(10, TimeUnit.SECONDS))
            session.stop()
            assertEquals(listOf(Stopped, Finished), fixture.until(events, Finished))
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `stop while idle sends nothing`() {
        val agent = FakeAgent.of("value")
        fixture.start(agent).use { session ->
            session.stop()
            fixture.run(session, "int x = 5;", 0)
            session.stop()
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `an error from the agent ends the evaluation with it as error output`() {
        // busy.jsonl lines 4 and 5: the agent refuses an evaluation while it runs one
        val agent = FakeAgent.of("busy", only = listOf(1, 4, 5, 7, 8))
        fixture.start(agent).use { session ->
            assertEquals(listOf(Output("busy", true), Finished), fixture.run(session, "1", 0))
            assertFalse(session.running)
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `output after the evaluation goes to the session`() {
        val agent = FakeAgent.of("late-output")
        val text =
            // a snippet's text, no sleep of the test
            "new Thread(() -> { try { Thread.sleep(500); } catch (Exception e) {} " +
                "System.out.println(\"late\"); }).start()"
        fixture.start(agent).use { session ->
            assertEquals(listOf(SnippetStarted(0, 107, 0), Finished), fixture.run(session, text, 0))
            assertEquals(SessionEvent.LateOutput("late\n", false), fixture.nextSessionEvent())
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `completions come with their anchor in the document`() {
        val agent = FakeAgent.of("completion")
        fixture.start(agent).use { session ->
            assertEquals(
                76,
                session
                    .complete("\"abc\".", 6)
                    .get(10, TimeUnit.SECONDS)
                    .items.size,
            )
            assertEquals(
                Completions(6, listOf(JShellCompletion("length()", true))),
                session.complete("\"abc\".len", 9).get(10, TimeUnit.SECONDS),
            )
            fixture.run(session, "String greeting = \"hi\";", 0)
            val text = "String greeting = \"hi\";\ngreeting."
            assertEquals(33, session.complete(text, 33).get(10, TimeUnit.SECONDS).anchor)
            assertEquals(
                6,
                session
                    .complete(text + "to", 35)
                    .get(10, TimeUnit.SECONDS)
                    .items.size,
            )
            assertEquals(
                Completions(
                    16,
                    List(4) {
                        JShellCompletion("max(", true)
                    },
                ),
                session.complete("int n = 3;\nMath.ma", 18).get(10, TimeUnit.SECONDS),
            )
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `a completion the agent refuses fails`() {
        val agent = FakeAgent.of("complete-error")
        fixture.start(agent).use { session ->
            val failure = runCatching { session.complete("x", 5).get(10, TimeUnit.SECONDS) }.exceptionOrNull()
            assertEquals("bad request: complete needs text and a caret within it", failure?.cause?.message)
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `a listener that throws is logged and the events go on`() {
        val agent = FakeAgent.of("value")
        LogCapture(JShellSession::class.java.name).use { log ->
            fixture.start(agent).use { session ->
                val events = LinkedBlockingQueue<EvalEvent>()
                session.evaluate("int x = 5;", 0, 0) {
                    events.put(it)
                    if (it is SnippetStarted) throw IllegalStateException("listener broke")
                }
                assertEquals(
                    listOf(SnippetStarted(0, 10, 0), Value("x ==> 5"), Finished),
                    fixture.until(events, Finished),
                )
            }
            assertEquals(listOf("listener broke"), log.thrown.map { it.message })
            assertEquals(listOf("A JShell listener failed"), log.messages())
        }
    }

    @Test
    fun `a listener that throws an Error is logged and the events go on`() {
        val agent = FakeAgent.of("value")
        LogCapture(JShellSession::class.java.name).use { log ->
            fixture.start(agent).use { session ->
                val events = LinkedBlockingQueue<EvalEvent>()
                session.evaluate("int x = 5;", 0, 0) {
                    events.put(it)
                    if (it is SnippetStarted) throw Error("listener error")
                }
                assertEquals(
                    listOf(SnippetStarted(0, 10, 0), Value("x ==> 5"), Finished),
                    fixture.until(events, Finished),
                )
            }
            assertEquals(listOf("listener error"), log.thrown.map { it.message })
        }
    }

    @Test
    fun `completions in flight at once each get their own answer`() {
        // completion.jsonl lines 1 to 5: both requests are out before either answer arrives
        val agent = FakeAgent.of("completion", only = (1..5).toList())
        fixture.start(agent).use { session ->
            agent.hold()
            val first = session.complete("\"abc\".", 6)
            val second = session.complete("\"abc\".len", 9)
            agent.release()
            assertEquals(76, first.get(10, TimeUnit.SECONDS).items.size)
            assertEquals(listOf(JShellCompletion("length()", true)), second.get(10, TimeUnit.SECONDS).items)
        }
    }

    @Test
    fun `an exception without frames in the session has no line`() {
        val agent = FakeAgent.of("thrown-no-line")
        val text =
            "throw new RuntimeException(\"quiet\") { public synchronized Throwable fillInStackTrace() { return this; } };"
        fixture.start(agent).use { session ->
            assertEquals(Thrown("REPL.\$JShell\$2\$1: quiet", null), fixture.run(session, text, 0)[1])
        }
        fixture.assertNoProblems(agent)
    }

    @Test
    fun `output without an evaluation's id goes to the session also while the next evaluation runs`() {
        // late-output.jsonl: the late output comes right after `finished`; the next evaluation starts in the Finished
        // listener, before the session reads on
        val agent = FakeAgent.of("late-output")
        val text =
            // a snippet's text, no sleep of the test
            "new Thread(() -> { try { Thread.sleep(500); } catch (Exception e) {} " +
                "System.out.println(\"late\"); }).start()"
        fixture.start(agent).use { session ->
            val second = LinkedBlockingQueue<EvalEvent>()
            session.evaluate(text, 0, 0) {
                if (it ==
                    Finished
                ) {
                    session.evaluate("1", 0, 0) { event -> second.put(event) }
                }
            }
            assertEquals(SessionEvent.LateOutput("late\n", false), fixture.nextSessionEvent())
            assertEquals(emptyList(), second.toList())
            assertTrue(session.running)
        }
    }

    @Test
    fun `events the session does not know are logged and skipped`() {
        // Hostile input, no agent sends it: value.jsonl with messages after its value whose event names have the hash
        // codes of the known ones, since a `when` over strings compares hash codes first.
        val names =
            listOf(
                "ready",
                "output",
                "snippetStarted",
                "value",
                "diagnostic",
                "thrown",
                "stopped",
                "finished",
                "completions",
                "error",
                "ended",
            )
        val strange =
            names.map {
                RecordedLine(
                    0,
                    false,
                    mapOf(
                        "id" to 1L,
                        "event" to collidingWith(it),
                        "text" to "x",
                        "feedback" to "x",
                        "reason" to "exit",
                    ),
                )
            }
        val lines = Recordings.load("value")
        val agent = FakeAgent(lines.take(4) + strange + lines.drop(4))
        LogCapture(JShellSession::class.java.name).use { log ->
            fixture.start(agent).use { session ->
                assertEquals(
                    listOf(SnippetStarted(0, 10, 0), Value("x ==> 5"), Finished),
                    fixture.run(session, "int x = 5;", 0),
                )
                assertEquals("25.0.5", session.javaVersion)
            }
            val json = JsonMapper.builder().build()
            assertEquals(
                names.map { "The JShell agent sent an unknown event: ${json.writeValueAsString(collidingWith(it))}" },
                log.messages().filter { it.startsWith("The JShell agent sent an unknown event: ") },
            )
        }
        assertEquals(SessionEvent.Ended("closed", null, null), fixture.nextSessionEvent())
    }
}
