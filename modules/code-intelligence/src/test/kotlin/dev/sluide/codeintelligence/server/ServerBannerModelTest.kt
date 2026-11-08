package dev.sluide.codeintelligence.server

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.codeintelligence.FakeInstaller
import dev.sluide.codeintelligence.FxServers
import dev.sluide.codeintelligence.InstallPrompts
import dev.sluide.codeintelligence.PySpec
import dev.sluide.codeintelligence.awaitTrue
import dev.sluide.languageservers.ServerAvailability
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TrustGate
import dev.sluide.projectdata.ProjectDataStore
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The banner of a bound editor as a model: which notice for which installer availability and server state, its texts,
 * Not Now per window and Don't Ask Again for every window (kept in the shared data file), and the buttons' actions.
 */
class ServerBannerModelTest {
    @TempDir
    lateinit var folder: Path

    private var fx: FxServers? = null

    @AfterEach
    fun close() {
        fx?.close()
    }

    private fun store() = ProjectDataStore(folder.resolve("projects"), folder.resolve("shared.json"), emptyList())

    private fun prompts() = InstallPrompts(store(), Runnable::run)

    @ParameterizedTest(name = "{0}, {1}, put off {2}")
    @MethodSource("notices")
    internal fun `the notice follows the installer first, then the server`(
        availability: ServerAvailability?,
        state: ServerState,
        putOff: Boolean,
        expected: ServerNotice?,
    ) {
        assertEquals(expected, noticeFor("Python", "ty", availability, state, putOff))
    }

    @Test
    fun `the banner texts name the language, the tool and the download size`() {
        assertEquals(
            "Code intelligence for Python needs ty (14 MB download).",
            noticeText(ServerNotice.Missing("Python", "ty", 14_200_000)),
        )
        assertEquals(
            "Code intelligence for Rust needs its language server.",
            noticeText(ServerNotice.Missing("Rust", null, null)),
        )
        assertEquals("Installing ty… 42 %", noticeText(ServerNotice.Installing("ty", 0.425)))
        assertEquals(
            "ty could not be installed: checksum mismatch",
            noticeText(ServerNotice.InstallFailed("Python", "ty", "checksum mismatch")),
        )
        assertEquals(
            "/a/ty is not an executable file. Change it in Settings → Languages → Python.",
            noticeText(ServerNotice.InstallFailed("Python", "ty", "/a/ty is not an executable file", canRetry = false)),
        )
        assertEquals(
            "The Python language server stopped: exit 1",
            noticeText(ServerNotice.Stopped("Python", "exit 1")),
        )
        assertEquals(
            "The Python language server waits for this project to be trusted.",
            noticeText(ServerNotice.Untrusted("Python")),
        )
    }

    /** A banner model of the editor of a window with [notNow], over a server of [spec]. */
    private fun model(
        servers: FxServers,
        spec: PySpec,
        notNow: ObservableSet<String>,
        prompts: InstallPrompts,
        tasks: BackgroundTasks? = null,
    ) = servers.onFx {
        ServerBannerModel(servers.servers.session(spec), notNow, prompts) { tasks }
    }

    @Test
    fun `Not Now hides the install banner in its window only, Don't Ask Again in every window and after a restart`() {
        val installer = FakeInstaller()
        val spec = PySpec(installer)
        val servers = FxServers(folder, spec).also { fx = it }
        val prompts = prompts()
        val window = FXCollections.observableSet<String>()
        val otherWindow = FXCollections.observableSet<String>()
        val model = model(servers, spec, window, prompts)
        val other = model(servers, spec, otherWindow, prompts)
        val missing = ServerNotice.Missing("Python", "ty", 14_200_000)
        assertEquals(missing, servers.onFx { model.notice.value })

        servers.onFx { model.notNow() }
        assertNull(servers.onFx { model.notice.value })
        assertEquals(missing, servers.onFx { other.notice.value }, "another window still asks")
        servers.onFx { installer.availability.set(ServerAvailability.Failed("offline")) }
        assertNull(servers.onFx { model.notice.value }, "an install that failed is put off too")

        servers.onFx { other.dontAskAgain() }
        assertNull(servers.onFx { other.notice.value })
        assertEquals(setOf("python"), servers.onFx { InstallPrompts(store(), Runnable::run).declined.toSet() })
        val restarted = model(servers, spec, FXCollections.observableSet(), InstallPrompts(store(), Runnable::run))
        assertNull(servers.onFx { restarted.notice.value }, "a new window after a restart does not ask")
    }

    @Test
    fun `the buttons install with the window's tasks, cancel, and restart`() {
        val installer = FakeInstaller(ServerAvailability.Installing(0.1))
        val spec = PySpec(installer)
        val servers = FxServers(folder, spec).also { fx = it }
        val tasks = BackgroundTasks.forTests()
        val model = model(servers, spec, FXCollections.observableSet(), prompts(), tasks)
        assertEquals(ServerNotice.Installing("ty", 0.1), servers.onFx { model.notice.value })

        servers.onFx { model.cancel() }
        servers.onFx { model.install() }
        assertEquals(1, installer.cancels)
        assertEquals(listOf<BackgroundTasks?>(tasks), installer.installs)

        servers.onFx { installer.availability.set(ServerAvailability.Ready) }
        servers.onFx { model.restart() }
        awaitTrue("restarted") { servers.fake.started.isNotEmpty() }
    }

    @Test
    fun `the banner follows the server's state once it is installed`() {
        val spec = PySpec(runsProjectCode = true)
        val servers = FxServers(folder, spec, trustGate = TrustGate { _, _ -> false }).also { fx = it }
        val model = model(servers, spec, FXCollections.observableSet(), prompts())
        assertNull(servers.onFx { model.notice.value })

        servers.onFx { servers.servers.session(spec).ensureStarted() }
        awaitTrue("untrusted") { servers.onFx { model.notice.value } == ServerNotice.Untrusted("Python") }
    }

    companion object {
        @JvmStatic
        fun notices(): List<Arguments> =
            listOf(
                Arguments.of(
                    ServerAvailability.Missing(14_200_000),
                    ServerState.Unavailable,
                    false,
                    ServerNotice.Missing("Python", "ty", 14_200_000),
                ),
                Arguments.of(ServerAvailability.Missing(1), ServerState.Unavailable, true, null),
                Arguments.of(
                    ServerAvailability.Installing(0.5),
                    ServerState.Unavailable,
                    false,
                    ServerNotice.Installing("ty", 0.5),
                ),
                Arguments.of(
                    ServerAvailability.Failed("offline"),
                    ServerState.Unavailable,
                    false,
                    ServerNotice.InstallFailed("Python", "ty", "offline"),
                ),
                Arguments.of(
                    ServerAvailability.Failed("/a/ty is not an executable file", canRetry = false),
                    ServerState.Unavailable,
                    false,
                    ServerNotice.InstallFailed("Python", "ty", "/a/ty is not an executable file", canRetry = false),
                ),
                Arguments.of(
                    ServerAvailability.Ready,
                    ServerState.Failed("exit 1"),
                    true,
                    ServerNotice.Stopped("Python", "exit 1"),
                ),
                Arguments.of(null, ServerState.Failed("exit 1"), false, ServerNotice.Stopped("Python", "exit 1")),
                Arguments.of(ServerAvailability.Ready, ServerState.Untrusted, false, ServerNotice.Untrusted("Python")),
                Arguments.of(ServerAvailability.Ready, ServerState.Running, false, null),
                Arguments.of(null, ServerState.Unavailable, false, null),
            )
    }
}
