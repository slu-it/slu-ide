package dev.sluide.plantuml

import dev.sluide.plantuml.engine.Drawing
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A `dot` that never closes its output: interrupts do not stop PlantUML there, and PlantUML's own timeout only starts
 * once the output closed, so only the watchdog ends the render.
 *
 * The fake is a native program compiled here: for a shell script `ProcessHandle.Info.command()` is the interpreter
 * (`/usr/bin/bash`), not the script, so the watchdog's exact match on the `dot` that was set could not find it. Linux
 * reports the executable's real path (`/proc/<pid>/exe`, measured: `/usr/bin/sort` for a link to it); that macOS
 * reports the path the process was started with comes from the JDK's source and is not measured. The watchdog matches
 * both.
 *
 * PlantUML keeps the `dot` path in one JVM-wide place and pitest runs every test in one JVM, so each engine that found
 * a `dot` sets its own path before each render: the fake never reaches another test's render. PlantUML also keeps the
 * answer of the first `dot -V` of the JVM for good (`GraphvizRuntimeEnvironment.dotVersion`) and lays out with
 * Smetana when it had no version: so the fake answers `-V` like Graphviz, and no test may use a `dot` that does not.
 * Each engine draws a sequence diagram first, so that PlantUML's cold start does not count against the 2 s timeout on
 * slow runners.
 */
class DotWatchdogTest {
    @TempDir
    lateinit var temp: Path

    private var fake: Path? = null

    private fun runsFake(process: ProcessHandle): Boolean {
        val command = process.info().command().getOrNull() ?: return false
        val dot = fake ?: return false
        return command == dot.toString() || command == dot.toRealPath().toString()
    }

    private fun fakes() =
        ProcessHandle
            .current()
            .children()
            .filter(::runsFake)
            .toList()

    @AfterEach
    fun killLeftovers() {
        fakes().forEach { it.destroyForcibly() }
    }

    private fun <T> awaitNotNull(get: () -> T?): T {
        val deadline = System.nanoTime() + 10_000_000_000
        while (true) {
            get()?.let { return it }
            assertTrue(System.nanoTime() < deadline, "not there within 10 s")
            Thread.sleep(10)
        }
    }

    /** An engine with the fake as its `dot` and a 2 s timeout, warmed up; [drawn] gets the thread of each draw. */
    private fun engine(drawn: (String) -> Unit = {}): PlantUml {
        val dot = fake.toString()
        val engine =
            PlantUml(
                GraphvizLocator({ dot }, emptyList()),
                2.seconds,
                ThreadFactory { Thread(it, "render").apply { isDaemon = true } },
                { source, firstLine, d, givenUp ->
                    drawn(source)
                    Drawing.draw(source, firstLine, d, givenUp)
                },
            )
        engine.render("@startuml\nAlice -> Bob : WARM\n@enduml\n").get(60, TimeUnit.SECONDS)
        return engine
    }

    @Test
    fun `a dot hung on the layout ends the render at the timeout, no dot is left, and the next render is drawn`() {
        fake = compiledFake() ?: return assumeTrue(false, "no C compiler to build the fake dot")
        val hungThread = CompletableFuture<Thread>()
        val engine = engine { source -> if ("class" in source) hungThread.complete(Thread.currentThread()) }

        val started = System.nanoTime()
        val hung = engine.render("@startuml\nclass A\nA --> B\n@enduml\n@startuml\nclass C\nC --> D\n@enduml\n")
        awaitNotNull { fakes().firstOrNull() }
        awaitNotNull { hung.getNow(null) }
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(Rendering(listOf(Diagram.Failed("Drawing took longer than 2 s", null)), false), hung.getNow(null))
        // About 2 s here; the bound leaves room for slow CI runners while staying below PlantUML's own 15 s.
        assertTrue(millis in 2_000..<10_000, "$millis ms")
        // Without the check before each export the abandoned render goes on to its next export and blocks on a new dot.
        val thread = hungThread.getNow(null)
        thread.join(10_000)
        assertFalse(thread.isAlive, "the abandoned render still runs")
        assertEquals(emptyList(), fakes())
        val next = engine.render("@startuml\nAlice -> Bob : NEXT\n@enduml\n").get(20, TimeUnit.SECONDS)
        assertTrue("NEXT" in next.onlyDrawn().lightSvg)
    }

    /**
     * A `dot` that prints a version for `-V`, and otherwise reads its input to the end and then sleeps with its output
     * open, as a `dot` hung on the layout does; `null` without a C compiler.
     */
    private fun compiledFake(): Path? {
        val source =
            Files.writeString(
                temp.resolve("fake-dot.c"),
                """
                #include <stdio.h>
                #include <string.h>
                #include <unistd.h>
                int main(int argc, char **argv) {
                    if (argc > 1 && strcmp(argv[1], "-V") == 0) {
                        fprintf(stderr, "dot - graphviz version 2.42.4 (0)\n");
                        return 0;
                    }
                    while (getchar() != EOF) {}
                    for (;;) pause();
                }
                """.trimIndent(),
            )
        val dot = temp.resolve("dot")
        val compiler =
            try {
                ProcessBuilder("cc", "-o", dot.toString(), source.toString()).redirectErrorStream(true).start()
            } catch (_: IOException) {
                // No cc on the PATH: the test is skipped.
                return null
            }
        val output = String(compiler.inputStream.readAllBytes())
        // A compiler that is there but fails is a broken test, not a reason to skip it.
        assertTrue(compiler.waitFor(60, TimeUnit.SECONDS) && compiler.exitValue() == 0, "cc failed: $output")
        return dot
    }
}
