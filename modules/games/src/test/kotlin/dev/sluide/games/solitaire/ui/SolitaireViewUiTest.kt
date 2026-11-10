package dev.sluide.games.solitaire.ui

import dev.sluide.games.common.GameClock
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.ui.firePress
import dev.sluide.games.solitaire.Cancellable
import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.KeyAction
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.SolitaireModel
import dev.sluide.games.solitaire.SolitairePreferences
import dev.sluide.games.solitaire.TableEvent
import dev.sluide.games.solitaire.solver.Deal
import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.event.EventType
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.ScrollBar
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Solitaire view to its model that need a scene: toolbar controls, key events on the table, the
 * guard and number dialogs' stubs, the hint outline, the overlay and the dealing label. The model runs on fakes (real
 * deals, direct executors). Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SolitaireViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: SolitaireModel
    private lateinit var view: SolitaireView
    private lateinit var stage: Stage
    private lateinit var preferences: SolitairePreferences
    private lateinit var zoomPreferences: GameZoomPreferences
    private val asked = mutableListOf<List<String>>()
    private var answer = false
    private val solves = mutableListOf<Int>()
    private val held = ArrayDeque<Runnable>()
    private val sync = Executor { it.run() }
    private lateinit var decoy: TextField

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        decoy = TextField()
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = SolitairePreferences(store, java.time.Duration.ZERO) { it.run() }
        model = modelOf(sync)
        zoomPreferences = GameZoomPreferences(store, java.time.Duration.ZERO, sync)
        view = viewOf(model)
        VBox.setVgrow(view.node, Priority.ALWAYS)
        stage.scene = Scene(VBox(decoy, view.node), 900.0, 700.0)
        stage.sizeToScene()
        stage.show()
    }

    private fun deal(
        mode: Mode,
        number: Int,
    ) = Deal(mode, number, Rules.of(mode).deal(Deals.deck(mode, number)), emptyList())

    private fun modelOf(dealing: Executor) =
        SolitaireModel(
            preferences,
            { mode, number, _ -> deal(mode, number) },
            { mode, number, _ -> if (number == UNWINNABLE) null else deal(mode, number) },
            { _, _, _ ->
                solves += 1
                SolveResult.Unknown
            },
            dealing,
            sync,
            sync,
            { _, _ -> Cancellable {} },
            GameClock(),
            { 7 },
        )

    private fun viewOf(model: SolitaireModel) =
        SolitaireView(model, zoomPreferences, animationSpeed = 0.0).also {
            it.confirm = { title, header, verb, content ->
                asked += listOf(title, header, verb, content)
                answer
            }
        }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun press(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(view.table, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    /** One stock draw, so the game has a move to undo and to lose. */
    private fun played(robot: FxRobot) = press(robot, KeyCode.D)

    private val stock = PileId(PileKind.STOCK)
    private val waste = PileId(PileKind.WASTE)

    @Test
    fun `the zoom resizes the cards, is saved and makes the table scroll`(robot: FxRobot) {
        fun cardWidth() =
            fx {
                view.node.scene.root
                    .layout()
                view.table.layoutNow().cardWidth
            }
        // The dealt columns fit the height of the tab, so Fit needs no bar.
        val fit = cardWidth()

        robot.interact { view.zoomControls.zoomTo(0.5) }
        assertEquals(floor(fit * 0.5), cardWidth())
        assertEquals(0.5, fx { zoomPreferences.zoom(SolitaireView.GAME_ID, ZoomRange.ABOVE_FIT) })

        robot.interact { view.zoomControls.zoomTo(2.0) }
        // Both bars show at 200% and take their width from the Fit: the Fit width loses up to 2 px (a bar is about 15 px), doubled, never wider.
        assertTrue(cardWidth() in floor(fit * 2.0) - 4.0..floor(fit * 2.0))
        val scroll = scrollPane()
        // The cards outgrow the tab: the table is larger than the viewport, which the scroll pane scrolls.
        assertTrue(fx { view.table.width > scroll.viewportBounds.width })
        assertTrue(fx { view.table.height > scroll.viewportBounds.height })
    }

    private fun scrollPane() = fx { view.node.lookup(".sol-scroll") as ScrollPane }

    private fun barShows(orientation: Orientation) =
        fx {
            scrollPane().lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().any {
                it.orientation == orientation && it.isVisible
            }
        }

    @Test
    fun `at Fit a table that scrolls vertically still fits the viewport in width`(robot: FxRobot) {
        val tall = emptyTable(Mode.KLONDIKE_DRAW_1, mapOf(PileId(PileKind.TABLEAU, 0) to pileOf(30, up = 24)))
        robot.interact {
            view.table.show(tall)
            view.node.scene.root
                .layout()
        }
        val scroll = scrollPane()

        // The column passes the height even squeezed, so the vertical bar shows; a horizontal one would hide a column.
        assertTrue(fx { view.table.height > scroll.viewportBounds.height })
        assertTrue(fx { view.table.width <= scroll.viewportBounds.width })
        assertFalse(barShows(Orientation.HORIZONTAL))
    }

    @Test
    fun `in a narrow tab only the horizontal bar shows`(robot: FxRobot) {
        robot.interact {
            (view.node as Region).maxWidth = 380.0
            view.node.scene.root
                .layout()
        }
        val scroll = scrollPane()

        // The smallest cards need about 392 px; the squeeze counts the bar's height, so the columns still fit.
        assertTrue(barShows(Orientation.HORIZONTAL))
        assertFalse(barShows(Orientation.VERTICAL))
        assertTrue(fx { view.table.height <= scroll.viewportBounds.height })
    }

    @Test
    fun `a key on the table reaches the model and the table shows the new state`(robot: FxRobot) {
        val before = fx { view.table.shown }

        played(robot)

        assertEquals(1, fx { model.moves })
        assertEquals(
            1,
            fx {
                view.table.shown
                    .pile(waste)
                    .cards.size
            },
        )
        assertNotEquals(before, fx { view.table.shown })
        assertEquals("Moves: 1", fx { view.movesLabel.text })
        assertEquals(fx { model.statusText }, fx { view.status.text })
    }

    private fun mouse(
        type: EventType<MouseEvent>,
        at: Pair<Double, Double>,
    ) = MouseEvent(
        type,
        at.first,
        at.second,
        at.first,
        at.second,
        MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        type == MouseEvent.MOUSE_PRESSED,
        false,
        false,
        true,
        false,
        true,
        null,
    )

    private fun stockCentre(): Pair<Double, Double> =
        fx {
            view.table
                .layoutNow()
                .slots
                .getValue(stock)
        }.let { it.x + it.w / 2 to it.y + it.h / 2 }

    @Test
    fun `a click on the table gives it the keyboard`(robot: FxRobot) {
        robot.interact { decoy.requestFocus() }
        assertSame(decoy, fx { stage.scene.focusOwner })

        robot.interact { Event.fireEvent(view.table, mouse(MouseEvent.MOUSE_PRESSED, 5.0 to 5.0)) }

        assertSame(view.table, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a click on the stock of the assembled view draws a card and focuses the table`(robot: FxRobot) {
        robot.interact { decoy.requestFocus() }
        val at = stockCentre()

        robot.interact {
            Event.fireEvent(view.table, mouse(MouseEvent.MOUSE_PRESSED, at))
            Event.fireEvent(view.table, mouse(MouseEvent.MOUSE_RELEASED, at))
        }

        assertEquals(1, fx { model.moves })
        assertSame(view.table, fx { stage.scene.focusOwner })
    }

    @Test
    fun `focusing the tab content passes the focus on to the table`(robot: FxRobot) {
        robot.interact { decoy.requestFocus() }

        robot.interact { view.node.requestFocus() }

        assertSame(view.table, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a mouse press on the empty area around the table leaves the focus on the table`(robot: FxRobot) {
        robot.interact { decoy.requestFocus() }

        robot.interact { firePress(view.node.lookup(".scroll-pane")) }

        assertSame(view.table, fx { stage.scene.focusOwner })
    }

    @Test
    fun `the table takes input only while the model does`(robot: FxRobot) {
        assertTrue(fx { view.table.inputEnabled })

        robot.interact { view.pauseButton.fire() }

        assertFalse(fx { view.table.inputEnabled })

        robot.interact { view.pauseButton.fire() }

        assertTrue(fx { view.table.inputEnabled })
    }

    @Test
    fun `arrow, space and escape keys show the cursor and the picked cards on the table`(robot: FxRobot) {
        press(robot, KeyCode.DOWN)

        assertEquals(1, fx { view.table.lookupAll(".sol-cursor").size })
        assertEquals(0, fx { view.table.lookupAll(".sol-picked").size })

        press(robot, KeyCode.SPACE)

        assertEquals(1, fx { view.table.lookupAll(".sol-picked").size })

        press(robot, KeyCode.ESCAPE)

        assertEquals(0, fx { view.table.lookupAll(".sol-picked").size })
    }

    @Test
    fun `New Game asks first when a game is under way and plays on when refused`(robot: FxRobot) {
        played(robot)
        val number = fx { model.game?.number }

        robot.interact { view.newGameButton.fire() }

        assertEquals(listOf(listOf("New Game", "Abandon this game?", "New Game", "Its score of 0 is lost.")), asked)
        assertEquals(number, fx { model.game?.number })
        assertEquals(1, fx { model.moves })

        answer = true
        robot.interact { view.newGameButton.fire() }

        assertEquals(2, asked.size)
        assertEquals(0, fx { model.moves })
    }

    @Test
    fun `F2 goes through the same guard`(robot: FxRobot) {
        played(robot)

        press(robot, KeyCode.F2)

        assertEquals("New Game", asked.single().first())
        assertEquals(1, fx { model.moves })
    }

    @Test
    fun `New Game needs no question before the first move`(robot: FxRobot) {
        robot.interact { view.newGameButton.fire() }

        assertTrue(asked.isEmpty())
    }

    @Test
    fun `the Game menu switches the variant and shows the menu of its option`(robot: FxRobot) {
        assertTrue(fx { view.drawMenu.isVisible })
        assertFalse(fx { view.suitsMenu.isVisible })
        assertEquals("Klondike", fx { view.gameMenu.text })

        robot.interact { view.gameMenu.items[2].fire() }

        assertEquals(Mode.SPIDER_1, fx { model.mode })
        assertEquals("Spider", fx { view.gameMenu.text })
        assertFalse(fx { view.drawMenu.isVisible })
        assertTrue(fx { view.suitsMenu.isVisible })
        assertEquals("1 Suit", fx { view.suitsMenu.text })

        robot.interact { view.suitsMenu.items[2].fire() }

        assertEquals(Mode.SPIDER_4, fx { model.mode })
        assertEquals("4 Suits", fx { view.suitsMenu.text })

        robot.interact { view.gameMenu.items[0].fire() }
        robot.interact { view.drawMenu.items[1].fire() }

        assertEquals(Mode.KLONDIKE_DRAW_3, fx { model.mode })
        assertEquals("Draw 3", fx { view.drawMenu.text })
    }

    @Test
    fun `changing the game, draw or suits asks first and keeps everything when refused`(robot: FxRobot) {
        played(robot)

        robot.interact { view.gameMenu.items[1].fire() }
        robot.interact { view.drawMenu.items[1].fire() }

        assertEquals(
            listOf("Change Game", "Change Draw"),
            asked.map { it.first() },
        )
        assertEquals(Mode.KLONDIKE_DRAW_1, fx { model.mode })
        assertEquals("Klondike", fx { view.gameMenu.text })
        assertEquals("Change", asked.first()[2])
    }

    @Test
    fun `Undo and Redo buttons follow the model and call it`(robot: FxRobot) {
        assertTrue(fx { view.undoButton.isDisable })
        played(robot)
        assertFalse(fx { view.undoButton.isDisable })

        robot.interact { view.undoButton.fire() }

        assertEquals(
            0,
            fx {
                view.table.shown
                    .pile(waste)
                    .cards.size
            },
        )
        assertTrue(fx { view.undoButton.isDisable })
        assertFalse(fx { view.redoButton.isDisable })

        robot.interact { view.redoButton.fire() }

        assertEquals(
            1,
            fx {
                view.table.shown
                    .pile(waste)
                    .cards.size
            },
        )
    }

    @Test
    fun `Restart asks first, then undoes every move`(robot: FxRobot) {
        assertTrue(fx { view.restartButton.isDisable })
        played(robot)

        robot.interact { view.restartButton.fire() }

        assertEquals(
            listOf(
                listOf(
                    "Restart",
                    "Start this deal over?",
                    "Restart",
                    "Every move is undone; the clock keeps running.",
                ),
            ),
            asked,
        )
        assertEquals(1, fx { model.moves })

        answer = true
        robot.interact { view.restartButton.fire() }

        assertEquals(0, fx { model.moves })
    }

    @Test
    fun `the Hint button starts the search`(robot: FxRobot) {
        robot.interact { view.hintButton.fire() }

        assertEquals(1, solves.size)
    }

    @Test
    fun `a hint event outlines the cards and the target`(robot: FxRobot) {
        robot.interact { model.onEvent(TableEvent.Hint(Move.Draw)) }

        assertEquals(2, fx { view.table.lookupAll(".sol-hint").size })
    }

    @Test
    fun `Pause covers the table and the button offers Resume`(robot: FxRobot) {
        assertFalse(fx { view.overlay.isVisible })

        robot.interact { view.pauseButton.fire() }

        assertTrue(fx { model.isManuallyPaused })
        assertTrue(fx { view.overlay.isVisible })
        assertEquals("Resume (P)", fx { view.pauseButton.tooltip.text })

        press(robot, KeyCode.P)

        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `Dealing shows while the first deal is made`(robot: FxRobot) {
        lateinit var slow: SolitaireView
        robot.interact { slow = viewOf(modelOf { held += it }) }

        assertTrue(fx { slow.dealing.isVisible })
        assertTrue(fx { slow.newGameButton.isDisable })
        assertTrue(fx { slow.dealNumberButton.isDisable })

        robot.interact { while (held.isNotEmpty()) held.removeFirst().run() }

        assertFalse(fx { slow.dealing.isVisible })
        assertFalse(fx { slow.newGameButton.isDisable })
        robot.interact { slow.dispose() }
    }

    @Test
    fun `the game menu can be used while a deal is made and the switch wins`(robot: FxRobot) {
        lateinit var slow: SolitaireView
        lateinit var slowModel: SolitaireModel
        robot.interact {
            slowModel = modelOf { held += it }
            slow = viewOf(slowModel)
            // Only the first deal: the next one is still being fetched, so New Game has to wait for it.
            held.removeFirst().run()
            slowModel.newGame()
        }
        assertTrue(fx { slowModel.isDealing })
        assertFalse(fx { slow.gameMenu.isDisable })
        assertFalse(fx { slow.drawMenu.isDisable })
        assertTrue(fx { slow.newGameButton.isDisable })

        robot.interact {
            slow.gameMenu.items[1].fire()
            while (held.isNotEmpty()) held.removeFirst().run()
        }

        assertEquals(Mode.FREECELL, fx { slowModel.game?.mode })
        robot.interact { slow.dispose() }
    }

    @Test
    fun `Deal Number asks for the number, then offers the alternative and plays it`(robot: FxRobot) {
        val alternatives = mutableListOf<Pair<Int, Int>>()
        robot.interact {
            view.askDealNumber = { UNWINNABLE }
            view.askAlternative = { number, other ->
                alternatives += number to other
                true
            }
        }

        robot.interact { view.dealNumberButton.fire() }

        assertEquals(listOf(UNWINNABLE to UNWINNABLE + 1), alternatives)
        assertEquals(UNWINNABLE + 1, fx { model.game?.number })
    }

    @Test
    fun `Deal Number asks to abandon a game under way first and stops when refused`(robot: FxRobot) {
        played(robot)
        var numberAsked = false
        robot.interact {
            view.askDealNumber = {
                numberAsked = true
                1
            }
        }

        robot.interact { view.dealNumberButton.fire() }

        assertEquals(listOf("Deal Number", "Abandon this game?", "Abandon"), asked.single().take(3))
        assertFalse(numberAsked)
    }

    @Test
    fun `a disposed view leaves both callbacks to the view that took over and pauses the game`(robot: FxRobot) {
        lateinit var second: SolitaireView
        robot.interact {
            second = viewOf(model)
            view.dispose()
            stage.scene.root = VBox(decoy, second.node)
            model.key(KeyAction.Stock)
            model.onEvent(TableEvent.Hint(Move.Draw))
        }

        // The second view shows the move and the hint; the first, disposed after it was replaced, shows neither.
        assertEquals("Moves: 1", fx { second.movesLabel.text })
        assertEquals(2, fx { second.table.lookupAll(".sol-hint").size })
        assertEquals("Moves: 0", fx { view.movesLabel.text })
        assertEquals(0, fx { view.table.lookupAll(".sol-hint").size })
        assertFalse(fx { model.hidden })

        robot.interact { second.dispose() }

        assertTrue(fx { model.hidden })
    }

    private companion object {
        const val UNWINNABLE = 500
    }
}
