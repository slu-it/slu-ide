package dev.sluide.java

import com.google.gson.JsonParser
import dev.sluide.languageservers.ProblemAction
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** How long the mirror waits in silence before it settles a job that may have been cancelled. */
internal val SETTLE: Duration = Duration.ofSeconds(10)

internal abstract class JavaImportsFixture {
    /** A clock the test moves on. */
    protected class MovingClock(
        @Volatile var current: Instant,
    ) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
    }

    @TempDir
    lateinit var dir: Path

    protected val access = ScriptedAccess()
    protected val states = ConcurrentHashMap<Path, ImportState>()
    protected val now = Instant.parse("2026-01-01T10:00:00Z")
    protected val clock = MovingClock(now)
    protected val queued = mutableListOf<Runnable>()
    protected var deferWorker = false
    protected val tickers = mutableListOf<Runnable>()
    protected val tickersStopped = mutableListOf<Runnable>()
    protected var jdk: String? = "25.0.5"
    protected val actionsMade = mutableListOf<Path>()
    protected val warnings = mutableListOf<String>()
    protected val outputTools = mutableMapOf<Path, BuildTool>()

    /** The output folders the offer asked git about, and the answers still to give. */
    protected val outputAsked = mutableListOf<List<Path>>()
    protected val outputAnswers = mutableListOf<(List<Path>?) -> Unit>()
    protected val outputOffers by lazy {
        val checks = OutputIgnoreChecks()
        checks.register(dir) { folders, done ->
            outputAsked += folders
            outputAnswers += done
        }
        OutputIgnoreOffers(dir, checks, outputTools::get, { false }, {}, Runnable::run, Runnable::run) {}
    }
    protected val imports by lazy {
        JavaImports(
            access,
            states,
            { task -> if (deferWorker) queued.add(task) else task.run() },
            Runnable::run,
            clock,
            { task ->
                tickers += task
                AutoCloseable { tickersStopped += task }
            },
            { jdk },
            { root ->
                actionsMade.add(root)
                listOf(ProblemAction.Callback("Retry Import") {})
            },
            SETTLE,
            { warnings.add(it) },
            outputOffers,
        )
    }

    /** Lets [seconds] pass and runs the ticker, if one runs. */
    protected fun tick(seconds: Long) {
        clock.current = clock.current.plusSeconds(seconds)
        if (ticking) tickers.last().run()
    }

    /** Whether the ticker that refreshes importing roots runs. */
    protected val ticking: Boolean get() = tickers.size > tickersStopped.size

    protected fun root(name: String): Path = dir.resolve(name)

    // The status text and the token sequences below are the ones jdtls 1.63.0 sent in the P0 runs (q5g.jsonl:
    // `language/status` {"type":"Message","message":"Updating workspace folders: Adding 1 folder(s), removing 0
    // folders."}, then "Adding 0 folder(s), removing 1 folders." for a removal), and the job order is that of
    // ProjectsManager.updateWorkspaceFolders (status, cancel the waiting jobs whose added set equals the removed set,
    // schedule a job; jobs run one after another).
    protected fun status(
        added: Int,
        removed: Int,
        type: String = "Message",
    ) = imports.onStatus(
        JsonParser.parseString(
            """{"type":"$type","message":"Updating workspace folders: Adding $added folder(s), removing $removed folders."}""",
        ),
    )

    /** One notification that removes and adds [root] (a refresh) and jdtls's status for it. */
    protected fun refresh(root: Path) {
        imports.onFolderRefreshed(root)
        status(1, 1)
    }

    /** The session's notification and jdtls's status message for it. */
    protected fun add(root: Path) {
        imports.onFolderAdded(root)
        status(1, 0)
    }

    protected fun remove(root: Path) {
        imports.onFolderRemoved(root)
        status(0, 1)
    }

    protected fun begin(
        token: String,
        title: String = "Updating workspace folders",
    ) = imports.onProgress(token, JsonParser.parseString("""{"kind":"begin","title":"$title"}"""))

    protected fun end(
        token: String,
        message: String = "Resolve plugin dependency",
    ) = imports.onProgress(token, JsonParser.parseString("""{"kind":"end","message":"$message"}"""))

    protected fun projects(vararg uris: String) {
        access.answers["java.project.getAll"] = { CompletableFuture.completedFuture(uris.toList()) }
    }

    protected fun event300(uri: String) =
        imports.onEvent(
            JsonParser.parseString(
                """{"eventType":300,"data":{"projectUri":"$uri","message":"Can't use Java 25.0.5 and Gradle 8.14.3 to import Gradle project x.","highestJavaVersion":"24","recommendedGradleVersion":"9.7.1"}}""",
            ),
        )

    protected fun error(
        file: Path,
        message: String,
    ) {
        access.diagnostics[file] =
            listOf(Diagnostic(Range(Position(0, 0), Position(0, 1)), message, DiagnosticSeverity.Error, "jdt"))
    }
}
