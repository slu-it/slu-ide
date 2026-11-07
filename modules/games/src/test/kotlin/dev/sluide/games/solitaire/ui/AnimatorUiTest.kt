package dev.sluide.games.solitaire.ui

import dev.sluide.games.common.GameClock
import dev.sluide.games.solitaire.Cancellable
import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.SolitaireModel
import dev.sluide.games.solitaire.SolitairePreferences
import dev.sluide.games.solitaire.Step
import dev.sluide.games.solitaire.Suit
import dev.sluide.games.solitaire.TableEvent
import dev.sluide.games.solitaire.solver.Deal
import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The animations on the assembled view with real durations: they transform nodes of a scene (translate, scale), which
 * only a scene shows. The model runs on fakes with direct executors. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class AnimatorUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: SolitaireModel
    private lateinit var view: SolitaireView
    private val sync = Executor { it.run() }
    private val waste = PileId(PileKind.WASTE)

    @Start
    fun start(stage: Stage) {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = SolitairePreferences(store, java.time.Duration.ZERO) { it.run() }
        model =
            SolitaireModel(
                preferences,
                { mode, number, _ -> dealOf(mode, number) },
                { mode, number, _ -> dealOf(mode, number) },
                { _, _, _ -> SolveResult.Unknown },
                sync,
                sync,
                sync,
                { _, _ -> Cancellable {} },
                GameClock(),
                { 7 },
            )
        view = SolitaireView(model)
        stage.scene = Scene(VBox(view.node), 900.0, 700.0)
        stage.show()
    }

    /** The first layout passes of the shown stage rebuild the nodes (resize); tests hold on to nodes. */
    @BeforeEach
    fun settle() {
        WaitForAsyncUtils.waitForFxEvents()
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun dealOf(
        mode: Mode,
        number: Int,
    ) = Deal(mode, number, Rules.of(mode).deal(Deals.deck(mode, number)), emptyList())

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun key(code: KeyCode) = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)

    private fun cardNodes() = view.table.children.filterIsInstance<ImageView>()

    private fun displaced() = cardNodes().filter { it.translateX != 0.0 || it.translateY != 0.0 }

    private fun awaitSettled() = WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS) { fx { displaced().isEmpty() } }

    @Test
    fun `a move starts displaced, slides to its place and then rests`(robot: FxRobot) {
        // the deal cascade of the first game comes first
        robot.interact { view.animator.finish() }
        var startedDisplaced = false
        robot.interact {
            Event.fireEvent(view.table, key(KeyCode.D))
            startedDisplaced = view.table
                .nodesOf(waste, 1)
                .single()
                .translateX != 0.0
        }

        assertTrue(startedDisplaced)
        awaitSettled()
        assertEquals(1, fx { model.moves })
    }

    @Test
    fun `a click during the deal cascade skips it and plays no move`(robot: FxRobot) {
        robot.interact { model.onEvent(TableEvent.Dealt(checkNotNull(model.game))) }
        assertTrue(fx { view.animator.cascading })
        assertTrue(fx { displaced().isNotEmpty() })
        val stock =
            fx {
                view.table
                    .layoutNow()
                    .slots
                    .getValue(PileId(PileKind.STOCK))
            }
        val at = stock.x + stock.w / 2 to stock.y + stock.h / 2

        robot.interact {
            Event.fireEvent(view.table, mouse(MouseEvent.MOUSE_PRESSED, at))
            Event.fireEvent(view.table, mouse(MouseEvent.MOUSE_RELEASED, at))
        }

        assertTrue(fx { displaced().isEmpty() })
        assertFalse(fx { view.animator.cascading })
        assertTrue(fx { view.table.inputEnabled })
        assertEquals(0, fx { model.moves })
        assertTrue(fx { inFinalLayout() })
    }

    /** Every card node shows its side (face of an up card, else the back) and is not scaled. */
    private fun inFinalLayout(): Boolean {
        val images = view.table.cardImages()
        return view.table.layoutNow().cards.all {
            val node = view.table.nodeAt(it.pile, it.index)
            node != null && node.scaleX == 1.0 && node.image === if (it.faceUp) images.face(it.card) else images.back()
        }
    }

    @Test
    fun `the table takes no input during the cascade and a key skips it without playing`(robot: FxRobot) {
        robot.interact { model.onEvent(TableEvent.Dealt(checkNotNull(model.game))) }
        assertFalse(fx { view.table.inputEnabled })

        robot.interact { Event.fireEvent(view.table, key(KeyCode.D)) }

        assertEquals(0, fx { model.moves })
        assertFalse(fx { view.animator.cascading })
        assertTrue(fx { view.table.inputEnabled })
    }

    @Test
    fun `showing a table during a slide leaves nothing displaced and the old node in place`(robot: FxRobot) {
        robot.interact { view.animator.finish() }
        lateinit var moving: ImageView
        robot.interact {
            Event.fireEvent(view.table, key(KeyCode.D))
            moving = view.table.nodesOf(waste, 1).single()
            assertNotEquals(0.0, moving.translateX)
            view.table.show(view.table.shown)
        }

        assertEquals(0.0, fx { moving.translateX })
        assertTrue(fx { displaced().isEmpty() })
    }

    @Test
    fun `showing a table during the deal cascade ends it and gives the input back`(robot: FxRobot) {
        robot.interact { model.onEvent(TableEvent.Dealt(checkNotNull(model.game))) }
        assertTrue(fx { view.animator.cascading })

        robot.interact { view.table.show(view.table.shown) }

        assertFalse(fx { view.animator.cascading })
        assertTrue(fx { view.table.inputEnabled })
        assertTrue(fx { displaced().isEmpty() })
        assertTrue(fx { inFinalLayout() })
    }

    private val ace = Card(1, Suit.SPADES)
    private val toFoundation = Move.Transfer(tableau(0), PileId(PileKind.FOUNDATION, 0))
    private val aceTable =
        emptyTable(
            Mode.KLONDIKE_DRAW_1,
            mapOf(tableau(0) to Pile(listOf(ace), 1), tableau(1) to Pile(listOf(Card(13, Suit.HEARTS)), 1)),
        )
    private val aceStep =
        Step(toFoundation, aceTable, Rules.of(Mode.KLONDIKE_DRAW_1).apply(aceTable, toFoundation))

    @Test
    fun `a card sliding to a foundation is drawn above the cards and falls back into its place after`(robot: FxRobot) {
        lateinit var node: ImageView
        var order = emptyList<Node>()
        var during = emptyList<Node>()
        var displacement = 0.0
        robot.interact { view.table.show(aceStep.outcome.table) }
        robot.interact {
            node = checkNotNull(view.table.nodeAt(PileId(PileKind.FOUNDATION, 0), 0))
            order = view.table.children.toList()
            model.onEvent(TableEvent.Moved(aceStep, false))
            during = view.table.children.toList()
            displacement = node.translateX + node.translateY
        }

        assertNotEquals(0.0, displacement)
        assertSame(node, during.last { it is ImageView })
        // above every card (the tableau comes after the foundations) and the slot outlines
        val at = during.indexOf(node)
        assertTrue(during.withIndex().none { (i, other) -> i > at && other !== node && other is ImageView })
        assertTrue(during.withIndex().all { (i, other) -> "sol-slot" !in other.styleClass || i < at })
        awaitSettled()
        assertEquals(order, fx { view.table.children.toList() })
    }

    @ParameterizedTest
    @ValueSource(strings = ["Undone", "Redone", "Restarted"])
    fun `undo, redo and restart events play their animation`(
        kind: String,
        robot: FxRobot,
    ) {
        val foundation = PileId(PileKind.FOUNDATION, 0)
        var displacement = 0.0
        var cascading = false
        robot.interact { view.table.show(if (kind == "Redone") aceStep.outcome.table else aceStep.before) }
        robot.interact {
            when (kind) {
                "Undone" -> model.onEvent(TableEvent.Undone(aceStep))
                "Redone" -> model.onEvent(TableEvent.Redone(aceStep))
                else -> model.onEvent(TableEvent.Restarted)
            }
            val node =
                when (kind) {
                    "Undone" -> view.table.nodeAt(tableau(0), 0)
                    "Redone" -> view.table.nodeAt(foundation, 0)
                    else -> null
                }
            displacement = node?.let { it.translateX + it.translateY } ?: 0.0
            cascading = view.animator.cascading
        }

        if (kind == "Restarted") assertTrue(cascading) else assertNotEquals(0.0, displacement)
        awaitSettled()
    }

    @Test
    fun `the win burst flies on Won and a key clears it without playing`(robot: FxRobot) {
        val won =
            emptyTable(
                Mode.KLONDIKE_DRAW_1,
                (0 until 4).associate {
                    PileId(PileKind.FOUNDATION, it) to Pile.allFaceUp(List(13) { r -> Card(r + 1, Suit.entries[it]) })
                },
            )
        robot.interact { view.table.show(won) }
        robot.interact { model.onEvent(TableEvent.Won(0, false)) }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { view.burstPane.children.isNotEmpty() } }
        assertTrue(fx { view.table.nodeAt(PileId(PileKind.FOUNDATION, 0), 12) != null })
        assertFalse(fx { view.table.nodeAt(PileId(PileKind.FOUNDATION, 0), 12)?.isVisible ?: true })

        robot.interact { Event.fireEvent(view.table, key(KeyCode.D)) }

        assertTrue(fx { view.burstPane.children.isEmpty() })
        assertEquals(0, fx { model.moves })
        assertTrue(fx { view.table.nodeAt(PileId(PileKind.FOUNDATION, 0), 12)?.isVisible ?: false })
    }

    private fun startBurst(robot: FxRobot) {
        val won =
            emptyTable(
                Mode.KLONDIKE_DRAW_1,
                (0 until 4).associate {
                    PileId(PileKind.FOUNDATION, it) to Pile.allFaceUp(List(13) { r -> Card(r + 1, Suit.entries[it]) })
                } + (tableau(0) to Pile(listOf(Card(13, Suit.SPADES)), 1)),
            )
        robot.interact { view.table.show(won) }
        robot.interact { model.onEvent(TableEvent.Won(0, false)) }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { view.burstPane.children.isNotEmpty() } }
    }

    @Test
    fun `a new event clears a running burst`(robot: FxRobot) {
        startBurst(robot)

        robot.interact { model.onEvent(TableEvent.Restarted) }

        assertTrue(fx { view.burstPane.children.isEmpty() })
    }

    @Test
    fun `disposing the view stops the burst and the animation`(robot: FxRobot) {
        startBurst(robot)
        robot.interact { view.animator.deal() }
        assertTrue(fx { view.animator.cascading })

        robot.interact { view.dispose() }

        assertTrue(fx { view.burstPane.children.isEmpty() })
        assertFalse(fx { view.animator.cascading })
        assertTrue(fx { displaced().isEmpty() })
    }

    private fun mouse(
        type: javafx.event.EventType<MouseEvent>,
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
}
