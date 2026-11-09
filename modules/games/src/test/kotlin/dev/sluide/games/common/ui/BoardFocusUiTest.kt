package dev.sluide.games.common.ui

import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.Callable
import kotlin.test.assertSame

// UI test: needs a scene for the focus owner and the scroll pane skin's own press behavior.
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BoardFocusUiTest {
    private lateinit var stage: Stage
    private lateinit var board: Pane
    private lateinit var other: Button
    private lateinit var scrollPane: ScrollPane
    private lateinit var label: Label
    private lateinit var root: BorderPane

    // Built here: JavaFX controls need the toolkit, which the extension starts before @Start.
    @Start
    fun start(stage: Stage) {
        this.stage = stage
        board = Pane().apply { isFocusTraversable = true }
        other = Button("other")
        scrollPane = ScrollPane(board)
        label = Label("status")
        root = BorderPane(scrollPane, other, null, label, null)
        stage.scene = Scene(root, 300.0, 300.0)
        stage.show()
        focusBoardOnPress(root, board)
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(Callable { block() }).get()

    @Test
    fun `a press on the scroll pane around the board focuses the board`(robot: FxRobot) {
        robot.interact { other.requestFocus() }

        robot.interact { firePress(scrollPane) }

        assertSame(board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a press on a child that does not take the focus focuses the board`(robot: FxRobot) {
        robot.interact { other.requestFocus() }

        robot.interact { firePress(label) }

        assertSame(board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a press that a child consumed leaves the focus alone`(robot: FxRobot) {
        robot.interact { label.setOnMousePressed { it.consume() } }
        robot.interact { other.requestFocus() }

        robot.interact { firePress(label) }

        assertSame(other, fx { stage.scene.focusOwner })
    }
}
