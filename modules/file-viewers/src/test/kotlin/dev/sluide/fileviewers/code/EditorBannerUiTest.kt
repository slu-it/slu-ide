package dev.sluide.fileviewers.code

import javafx.beans.property.SimpleStringProperty
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertTrue

/** A banner narrower than its message: the buttons keep their size and the message wraps. UI test: layout. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorBannerUiTest {
    private val container = VBox()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 300.0, 200.0)
        stage.show()
    }

    @Test
    fun `a long message wraps and never squeezes the buttons`(robot: FxRobot) {
        val button = Button("Edit Anyway")
        lateinit var bar: HBox
        robot.interact {
            bar =
                banner(
                    SimpleStringProperty(
                        "demo/__init__.py is library code (virtual environment .venv): read-only, as changes are " +
                            "lost on the next install.",
                    ),
                    button,
                )
            container.children.setAll(bar)
            container.layout()
        }
        WaitForAsyncUtils.waitForFxEvents()

        val (buttonWidth, buttonPref, labelHeight, lineHeight) =
            WaitForAsyncUtils
                .asyncFx<List<Double>> {
                    val label = bar.children.filterIsInstance<Label>().single()
                    listOf(button.width, button.prefWidth(-1.0), label.height, label.font.size)
                }.get()
        assertTrue(buttonWidth >= buttonPref - 0.5, "button kept its width: $buttonWidth < $buttonPref")
        assertTrue(labelHeight > 2 * lineHeight, "message wrapped: height $labelHeight for font $lineHeight")
    }
}
