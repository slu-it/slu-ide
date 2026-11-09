package dev.sluide.codeintelligence

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.languageservers.EnvironmentProblem
import dev.sluide.languageservers.FakeLanguageServers
import dev.sluide.languageservers.FakeServer
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerCommand
import dev.sluide.languageservers.ServerInstaller
import dev.sluide.languageservers.TrustGate
import dev.sluide.languageservers.WorkspaceStatus
import javafx.beans.property.SimpleObjectProperty
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A Python-like spec for `.py` files; [installer] decides the command when given. */
internal class PySpec(
    override val installer: FakeInstaller? = null,
    override val id: String = "python",
    override val name: String = "Python",
    override val runsProjectCode: Boolean = false,
    private val status: (Path?, Path) -> WorkspaceStatus? = { _, _ -> null },
    private val problem: (Path, Path) -> EnvironmentProblem? = { _, _ -> null },
) : LanguageServerSpec {
    override fun environmentProblem(
        file: Path,
        projectFolder: Path,
    ) = problem(file, projectFolder)

    override fun workspaceStatus(
        folder: Path?,
        projectFolder: Path,
    ) = status(folder, projectFolder)

    override val trustCheckId: String? get() = if (runsProjectCode) "test" else null

    override fun matches(file: Path) = file.fileName.toString().endsWith(".py")

    override fun languageId(file: Path) = "python"

    override fun command(): ServerCommand? =
        if (installer == null || installer.availability.value == ServerAvailability.Ready) {
            ServerCommand(Path.of("fake-server"))
        } else {
            null
        }
}

/** A [ServerInstaller] the test drives: [availability] is set by hand, [commandChanged] tells the listeners. */
internal class FakeInstaller(
    initial: ServerAvailability = ServerAvailability.Missing(14_200_000),
    override val toolName: String? = "ty",
) : ServerInstaller {
    override val availability = SimpleObjectProperty(initial)
    val installs = mutableListOf<BackgroundTasks?>()
    var cancels = 0
    private val listeners = mutableListOf<() -> Unit>()

    override fun install(tasks: BackgroundTasks?) {
        installs += tasks
    }

    override fun cancel() {
        cancels++
    }

    override fun onCommandChanged(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun commandChanged() = listeners.toList().forEach { it() }
}

/** A fake server that syncs incrementally and wants saves without text, as ty does. */
internal fun syncingServer() =
    FakeServer().apply {
        capabilities =
            ServerCapabilities().apply {
                textDocumentSync =
                    Either.forRight(
                        TextDocumentSyncOptions().apply {
                            openClose = true
                            change = TextDocumentSyncKind.Incremental
                            save = Either.forRight(SaveOptions(false))
                        },
                    )
            }
    }

/**
 * Fake language servers whose results and states arrive on a single thread that plays the FX thread: tests run every
 * call into the code intelligence there with [onFx].
 */
internal class FxServers(
    folder: Path,
    vararg specs: LanguageServerSpec,
    trustGate: TrustGate = TrustGate { _, _ -> true },
    newServer: () -> FakeServer = ::syncingServer,
) : AutoCloseable {
    private val fx = Executors.newSingleThreadExecutor { Thread(it, "test-fx").apply { isDaemon = true } }

    val fake = FakeLanguageServers(specs.toList(), folder, trustGate, fx::execute) { newServer() }
    val servers get() = fake.servers

    fun <T> onFx(action: () -> T): T = fx.submit(action).get(5, TimeUnit.SECONDS)

    /** Waits until everything dispatched to the FX thread before has run. */
    fun idle() = onFx {}

    override fun close() {
        fake.close()
        fx.shutdownNow()
    }
}

/** Waits up to 5 s for [condition]. */
internal fun awaitTrue(
    what: String,
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}
