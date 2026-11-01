package dev.sluide.docker.run

import dev.sluide.docker.tab.awaitTrue
import dev.sluide.docker.tab.onFx
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.DialogPane
import javafx.scene.control.Labeled
import javafx.scene.input.Clipboard
import javafx.scene.layout.Region
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Run Container form's binding of its controls to the pure command functions (DockerRunCommandTest has their
 * cases): typing changes the preview, invalid input replaces it with the message and disables Run's condition, Copy
 * puts the self-contained command on the clipboard, volume names are offered. A unit test cannot see the controls.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class RunDialogUiTest {
    private lateinit var form: RunForm

    @Start
    fun start(stage: Stage) {
        form =
            RunForm(
                RunOptions("nginx:1", network = "shop"),
                listOf("bridge", "shop"),
                listOf("data", "cache"),
                "docker",
                Path.of("/work/shop"),
                Path.of("/home/me"),
            )
        stage.scene = Scene(form.node, 700.0, 600.0)
        stage.show()
    }

    @Test
    fun `typing into the fields updates the command preview and masks values`(robot: FxRobot) {
        assertEquals("docker run -d --network shop nginx:1", onFx { form.preview.text })

        robot.interact {
            form.name.text = "web"
            form.ports.setAll(listOf("8080:80"))
            form.environment.setAll(listOf(RunEnvVar("TOKEN", "s3cret")))
            form.volumes.setAll(listOf(RunVolume("./html", "/html", readOnly = true)))
            form.command.text = "nginx -g 'daemon off;'"
            form.detached.isSelected = false
            form.removeWhenStopped.isSelected = true
        }

        assertEquals(
            "docker run --rm --name web -p 8080:80 -e 'TOKEN=••••' -v /work/shop/html:/html:ro --network shop " +
                "nginx:1 " +
                "nginx -g 'daemon off;'",
            onFx { form.preview.text },
        )
        assertTrue(onFx { form.valid.get() })
    }

    @Test
    fun `the plus button adds a row that counts as input`(robot: FxRobot) {
        robot.interact { form.ports.addButton.fire() }

        assertEquals(listOf(""), onFx { form.ports.values() })
        assertTrue(onFx { form.valid.get() }, "an empty row is ignored")
    }

    @Test
    fun `a volume row in a narrow form leaves the labels and its buttons whole`() {
        val truncated =
            onFx {
                form.volumes.addButton.fire()
                val root = form.node as Region
                root.applyCss()
                root.resize(NARROW, root.height)
                root.layout()
                listOf(".label", ".button", ".check-box")
                    .flatMap { root.lookupAll(it) }
                    .filterIsInstance<Labeled>()
                    .filter { it.isVisible && !it.text.isNullOrEmpty() && it.isTextTruncated }
                    .map { it.text }
            }

        assertEquals(emptyList<String>(), truncated)
    }

    @Test
    fun `an invalid port shows its message in place of the preview and disables Copy and Run`(robot: FxRobot) {
        robot.interact { form.ports.setAll(listOf("99999")) }

        assertFalse(onFx { form.valid.get() })
        assertTrue(onFx { form.problem.isVisible && "99999" in form.problem.text })
        assertFalse(onFx { form.preview.isVisible })
        assertTrue(onFx { form.copy.isDisable })

        robot.interact { form.ports.setAll(listOf("80")) }

        assertTrue(onFx { form.valid.get() && form.preview.isVisible && !form.problem.isVisible })
    }

    @Test
    fun `Copy puts the command with the variable values on the clipboard`(robot: FxRobot) {
        robot.interact { form.environment.setAll(listOf(RunEnvVar("MSG", "it's here"))) }

        robot.interact { form.copy.fire() }

        assertEquals(
            "'docker' 'run' '-d' '-e' 'MSG=it'\\''s here' '--network' 'shop' 'nginx:1'",
            onFx { Clipboard.getSystemClipboard().string },
        )
    }

    @Test
    fun `the network list starts with the default and the volume source offers the volume names`(robot: FxRobot) {
        robot.interact { form.volumes.setAll(listOf(RunVolume("", ""))) }

        assertEquals(listOf("(default)", "bridge", "shop"), onFx { form.network.items.toList() })
        val source =
            onFx {
                robot
                    .lookup(
                        ".combo-box",
                    ).queryAllAs(javafx.scene.control.ComboBox::class.java)
                    .first { it.isEditable }
            }
        assertEquals(listOf("data", "cache"), onFx { source.items.toList() })

        robot.interact { form.network.value = "(default)" }

        assertEquals(null, onFx { form.options().network })
    }

    private fun newForm() =
        RunForm(
            RunOptions("nginx:1"),
            listOf("bridge"),
            listOf("data"),
            "docker",
            Path.of("/work/shop"),
            Path.of("/home/me"),
        )

    /** Shows the real dialog around [form] as a nested event loop on the FX thread; its result arrives in the future. */
    private fun showDialog(form: RunForm): Pair<CompletableFuture<RunOptions?>, DialogPane> {
        val result = CompletableFuture<RunOptions?>()
        Platform.runLater { result.complete(showRunDialog({ null }, form)) }

        fun shown() =
            Window
                .getWindows()
                .filter { it.isShowing }
                .map { it.scene.root }
                .filterIsInstance<DialogPane>()
        awaitTrue("the dialog") { onFx { shown().isNotEmpty() } }
        return result to onFx { shown().single() }
    }

    private fun DialogPane.button(text: String) =
        lookupButton(buttonTypes.single { it.text == text }) as javafx.scene.control.Button

    @Test
    fun `the dialog's Run is disabled for invalid input and returns the options without blank rows`() {
        val form = onFx { newForm() }
        val (result, pane) = showDialog(form)

        onFx { form.ports.setAll(listOf("99999")) }
        assertTrue(onFx { pane.button("Run").isDisabled }, "an invalid port disables Run")

        onFx {
            form.ports.setAll(listOf("8080:80", " "))
            form.environment.setAll(listOf(RunEnvVar("", ""), RunEnvVar("A", "1")))
        }
        assertFalse(onFx { pane.button("Run").isDisabled })
        onFx { pane.button("Run").fire() }

        assertEquals(
            RunOptions("nginx:1", ports = listOf("8080:80"), environment = listOf(RunEnvVar("A", "1"))),
            result.get(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `the dialog's Cancel returns nothing`() {
        val form = onFx { newForm() }
        val (result, pane) = showDialog(form)

        onFx { pane.button("Cancel").fire() }

        assertNull(result.get(5, TimeUnit.SECONDS))
    }

    private companion object {
        /** About the width of the dialog's form at its default size. */
        const val NARROW = 650.0
    }
}
