package dev.sluide.docker.compose

import dev.sluide.docker.EngineState
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EngineInfo
import dev.sluide.docker.model.container
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The decisions of the Compose pane: enablement, notes, questions and the inputs of its fields. */
class ComposeRulesTest {
    private val available =
        EngineState.Available(
            EngineInfo(EngineFlavor.DOCKER, "29.8.1", "29.8.1", "Docker", "default"),
        )
    private val config = parseComposeConfig(shopConfig())

    private fun service(
        vararg states: ContainerState,
        orphan: Boolean = false,
    ): ServiceState {
        val containers = states.mapIndexed { i, state -> container("shop-web-${i + 1}", state = state) }
        return ServiceState("web", if (orphan) null else config.services.first { it.name == "web" }, containers, "")
    }

    @Test
    fun `commands need an available engine and a read configuration`() {
        assertTrue(commandsEnabled(available, ConfigLoad.Loaded(config)))
        assertFalse(commandsEnabled(available, ConfigLoad.Failed("broken")))
        assertFalse(commandsEnabled(available, ConfigLoad.Loading))
        assertFalse(commandsEnabled(EngineState.Unavailable("down"), ConfigLoad.Loaded(config)))
    }

    @Test
    fun `the engine note says why Docker cannot be used`() {
        assertNull(engineNote(available))
        assertEquals("Connecting to Docker…", engineNote(EngineState.Checking))
        assertEquals(
            "Docker is not reachable: Cannot connect to the Docker daemon",
            engineNote(EngineState.Unavailable("Cannot connect to the Docker daemon")),
        )
        assertEquals(
            "docker was not found on the PATH",
            engineNote(EngineState.NotFound("docker was not found on the PATH")),
        )
        assertEquals("Up waits for the configuration…", toolbarNote(available, waitingUp = true))
        assertEquals("Connecting to Docker…", toolbarNote(EngineState.Checking, waitingUp = true))
        assertNull(toolbarNote(available, waitingUp = false))
    }

    @Test
    fun `the services area notes reading, failures and an empty file`() {
        assertEquals("Reading the compose file…", configNote(ConfigLoad.Loading))
        assertEquals("✖ The compose file cannot be used: yaml: line 2", configNote(ConfigLoad.Failed("yaml: line 2")))
        assertNull(configNote(ConfigLoad.Loaded(config)))
        assertEquals(
            "The file defines no services.",
            configNote(ConfigLoad.Loaded(config.copy(services = emptyList()))),
        )
        assertEquals("Also uses compose.override.yaml", overrideNote(Path.of("/p/compose.override.yaml")))
        assertNull(overrideNote(null))
    }

    @Test
    fun `row actions follow the containers' states`() {
        val all = ServiceAction.entries.toSet()
        assertEquals(setOf(ServiceAction.UP), serviceActions(service(), enabled = true), "not created")
        assertEquals(all, serviceActions(service(ContainerState.RUNNING, ContainerState.EXITED), enabled = true))
        assertEquals(
            setOf(ServiceAction.UP, ServiceAction.LOGS),
            serviceActions(service(ContainerState.EXITED), enabled = true),
        )
        assertEquals(
            setOf(ServiceAction.UP, ServiceAction.STOP, ServiceAction.LOGS),
            serviceActions(service(ContainerState.RESTARTING), enabled = true),
        )
        assertEquals(
            all - ServiceAction.UP,
            serviceActions(service(ContainerState.RUNNING, orphan = true), enabled = true),
        )
        assertEquals(emptySet(), serviceActions(service(ContainerState.RUNNING), enabled = false))
    }

    @Test
    fun `the questions name the file, the variables and the project`() {
        assertEquals("compose.yaml has unsaved changes.", unsavedQuestion("compose.yaml"))
        assertEquals(
            "Variable GREETING is not set and defaults to a blank string. Up anyway?",
            unresolvedQuestion(setOf("GREETING")),
        )
        assertEquals(
            "Variables A, B are not set and default to a blank string. Up anyway?",
            unresolvedQuestion(setOf("B", "A")),
        )
        assertEquals("⚠ Not set, default to a blank string: A, B", unresolvedNote(setOf("B", "A")))
        assertNull(unresolvedNote(emptySet()))
        assertTrue("shop" in downVolumesQuestion("shop"))
    }

    @Test
    fun `the profiles label names the chosen ones`() {
        assertEquals("No profiles", profilesLabel(emptySet(), emptySet()))
        assertEquals("Profiles: none", profilesLabel(emptySet(), setOf("debug")))
        assertEquals("Profiles: a, debug", profilesLabel(setOf("debug", "a"), setOf("debug")))
    }

    @ParameterizedTest
    @CsvSource("'',true,", "'  shop ',true,shop", "my_app-2,true,my_app-2", "Shop,false,", "-p,false,", "a b,false,")
    fun `the project name field gives a valid name, compose's own when empty, or nothing`(
        text: String,
        valid: Boolean,
        name: String?,
    ) {
        val expected = if (valid) ProjectNameInput.Valid(name) else ProjectNameInput.Invalid
        assertEquals(expected, projectNameInput(text))
    }

    @Test
    fun `variables with invalid names are left out`() {
        assertEquals(
            listOf(ComposeVariable("A", "1"), ComposeVariable("_B2", "")),
            variablesOf(listOf("A" to "1", "" to "x", "1X" to "y", "_B2" to "", "A-B" to "z")),
        )
    }

    @Test
    fun `env files are stored relative to the folder, and the first one keeps an existing default`() {
        val folder = Path.of("/p/shop")
        assertEquals("env/dev.env", envFileEntry(folder, Path.of("/p/shop/env/dev.env")))
        assertEquals("/other/prod.env", envFileEntry(folder, Path.of("/other/prod.env")))

        assertEquals(listOf(".env", "dev.env"), withEnvFile(null, "dev.env", defaultExists = true))
        assertEquals(listOf("dev.env"), withEnvFile(null, "dev.env", defaultExists = false))
        assertEquals(listOf("a.env", "dev.env"), withEnvFile(listOf("a.env"), "dev.env", defaultExists = true))
        assertEquals(listOf("dev.env"), withEnvFile(listOf("dev.env"), "dev.env", defaultExists = false))

        assertEquals("Uses .env (default)", envFilesPlaceholder(null, defaultExists = true))
        assertEquals("Uses .env (default; there is none beside the file)", envFilesPlaceholder(null, false))
        assertEquals("No env files: none is read.", envFilesPlaceholder(emptyList(), true))
    }
}
