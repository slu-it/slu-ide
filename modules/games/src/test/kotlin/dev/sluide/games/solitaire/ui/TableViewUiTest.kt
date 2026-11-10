package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.KlondikeRules
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.cards
import javafx.application.ColorScheme
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.image.Image
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class FakeHost(
    override val table: Table,
) : TableHost {
    override val rules: Rules = KlondikeRules(Mode.KLONDIKE_DRAW_1)
    val played = mutableListOf<Move>()
    var stockClicks = 0

    override fun play(move: Move): Boolean {
        played += move
        return true
    }

    override fun stockClicked() {
        stockClicks++
    }
}

/**
 * Mouse routing of the table view on real nodes: events are fired at the view (no OS input), the host records what
 * the view asks for. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TableViewUiTest {
    private val robot = FxRobot()
    private val stock = PileId(PileKind.STOCK)
    private val foundation3 = PileId(PileKind.FOUNDATION, 3)
    private val host =
        FakeHost(
            emptyTable(
                Mode.KLONDIKE_DRAW_1,
                mapOf(
                    stock to Pile(cards("2C 3C")),
                    tableau(0) to Pile.allFaceUp(cards("6S")),
                    tableau(1) to Pile.allFaceUp(cards("5H")),
                    tableau(2) to Pile.allFaceUp(cards("AD")),
                    tableau(3) to Pile.allFaceUp(cards("9S 8H 7C")),
                    tableau(4) to Pile(cards("KD 4C"), 1),
                    tableau(5) to Pile.allFaceUp(cards("5C 9D")),
                    foundation3 to Pile.allFaceUp(cards("4D 5D")),
                ),
            ),
        )
    private lateinit var view: TableView

    @Start
    fun start(stage: Stage) {
        val scroll = ScrollPane()
        view = TableView(host, scroll, slideBack = Duration.ZERO, hintDuration = Duration.millis(1500.0))
        scroll.content = view
        scroll.isFitToWidth = true
        scroll.isFitToHeight = true
        stage.scene = Scene(scroll, 1000.0, 700.0)
        stage.show()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> onFx(block: () -> T): T {
        var result: Any? = null
        robot.interact { result = block() }
        return result as T
    }

    private fun rectOf(pile: PileId) =
        onFx {
            view
                .layoutNow()
                .cards
                .last { it.pile == pile }
                .rect
        }

    private fun firstRectOf(pile: PileId) =
        onFx {
            view
                .layoutNow()
                .cards
                .first { it.pile == pile }
                .rect
        }

    private fun centre(pile: PileId): Pair<Double, Double> = rectOf(pile).let { it.x + it.w / 2 to it.y + it.h / 2 }

    private fun slotCentre(pile: PileId): Pair<Double, Double> =
        onFx { view.layoutNow().slots.getValue(pile) }.let { it.x + it.w / 2 to it.y + it.h / 2 }

    private fun fire(
        type: EventType<MouseEvent>,
        at: Pair<Double, Double>,
        clicks: Int = 1,
        button: MouseButton = MouseButton.PRIMARY,
    ) {
        val event =
            MouseEvent(
                type,
                at.first,
                at.second,
                at.first,
                at.second,
                button,
                clicks,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                true,
                null,
            )
        robot.interact { Event.fireEvent(view, event) }
    }

    private fun drag(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
    ) {
        startDrag(from, to)
        fire(MouseEvent.MOUSE_RELEASED, to)
    }

    private fun startDrag(
        from: Pair<Double, Double>,
        to: Pair<Double, Double>,
    ) {
        fire(MouseEvent.MOUSE_PRESSED, from)
        fire(MouseEvent.MOUSE_DRAGGED, from.first + 10 to from.second + 10)
        fire(MouseEvent.MOUSE_DRAGGED, to)
    }

    private fun click(
        at: Pair<Double, Double>,
        clicks: Int = 1,
    ) {
        fire(MouseEvent.MOUSE_PRESSED, at, clicks)
        fire(MouseEvent.MOUSE_RELEASED, at, clicks)
    }

    private fun settled() = WaitForAsyncUtils.waitForFxEvents()

    /** The outlines with the style class, as rectangles. */
    private fun outlines(styleClass: String): List<Rect> =
        onFx {
            view.children
                .filterIsInstance<Region>()
                .filter { styleClass in it.styleClass }
                .map { Rect(it.layoutX, it.layoutY, it.width, it.height) }
        }

    private fun translateOf(
        pile: PileId,
        count: Int = 1,
    ): List<Double> = onFx { view.nodesOf(pile, count).map { it.translateX + it.translateY } }

    private fun pixels(image: Image): List<Int> =
        (0 until image.height.toInt()).flatMap { y ->
            (0 until image.width.toInt()).map { x -> image.pixelReader.getArgb(x, y) }
        }

    private fun awaitNoOutline(styleClass: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (outlines(styleClass).isNotEmpty()) {
            check(System.nanoTime() < deadline) { "$styleClass is still shown" }
            Thread.sleep(20) // polling a timer, bounded by the deadline
        }
    }

    @Test
    fun `dragging a card onto a legal pile plays the transfer`() {
        settled()
        drag(centre(tableau(1)), slotCentre(tableau(0)))
        assertEquals(listOf<Move>(Move.Transfer(tableau(1), tableau(0), 1)), host.played)
    }

    @Test
    fun `an illegal drop plays nothing and the card goes back`() {
        settled()
        drag(centre(tableau(0)), slotCentre(tableau(1)))
        assertTrue(host.played.isEmpty())
        assertEquals(listOf(0.0), translateOf(tableau(0)))
    }

    @Test
    fun `a release outside the view plays nothing`() {
        settled()
        drag(centre(tableau(1)), -50.0 to 400.0)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `the legal targets are outlined while dragging and not after the release`() {
        settled()
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        assertEquals(listOf(rectOf(tableau(0))), outlines("sol-drop-ok"))
        fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        assertTrue(outlines("sol-drop-ok").isEmpty())
    }

    @Test
    fun `another button pressed during a drag changes nothing`() {
        settled()
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        fire(MouseEvent.MOUSE_PRESSED, slotCentre(tableau(3)), button = MouseButton.SECONDARY)
        assertEquals(1, outlines("sol-drop-ok").size)
        assertNotEquals(listOf(0.0), translateOf(tableau(1)))
        fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        assertEquals(1, host.played.size)
    }

    @Test
    fun `a click plays the move the chooser picks`() {
        settled()
        click(centre(tableau(2)))
        assertEquals(listOf<Move>(Move.Transfer(tableau(2), PileId(PileKind.FOUNDATION, 0), 1)), host.played)
    }

    @Test
    fun `the second click of a double-click on a card is ignored`() {
        settled()
        click(centre(tableau(2)), clicks = 2)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `a click on a foundation card plays nothing`() {
        settled()
        click(centre(foundation3))
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `a click on the stock asks the host even as a quick repeated click`() {
        settled()
        click(centre(stock))
        click(centre(stock), clicks = 2)
        assertEquals(2, host.stockClicks)
        click(slotCentre(PileId(PileKind.WASTE)))
        assertEquals(2, host.stockClicks)
    }

    @Test
    fun `dragging off the stock is not a click`() {
        settled()
        drag(centre(stock), slotCentre(tableau(0)))
        assertEquals(0, host.stockClicks)
    }

    @Test
    fun `a face-down card and a card outside the run cannot be picked up`() {
        settled()
        for (pile in listOf(tableau(4), tableau(5))) {
            val rect = firstRectOf(pile)
            val from = rect.x + 10 to rect.y + 3
            startDrag(from, slotCentre(tableau(0)))
            assertEquals(listOf(0.0, 0.0), translateOf(pile, 2))
            assertTrue(outlines("sol-drop-ok").isEmpty())
            fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        }
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `disabling input between press and release of a click plays nothing`() {
        settled()
        val at = centre(tableau(2))
        fire(MouseEvent.MOUSE_PRESSED, at)
        robot.interact { view.inputEnabled = false }
        fire(MouseEvent.MOUSE_RELEASED, at)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `a press that moves less than the threshold is still a click`() {
        settled()
        val at = centre(tableau(1))
        fire(MouseEvent.MOUSE_PRESSED, at)
        fire(MouseEvent.MOUSE_DRAGGED, at.first + 2 to at.second + 2)
        assertEquals(listOf(0.0), translateOf(tableau(1)))
        assertTrue(outlines("sol-drop-ok").isEmpty())
        fire(MouseEvent.MOUSE_RELEASED, at.first + 2 to at.second + 2)
        assertEquals(listOf<Move>(Move.Transfer(tableau(1), tableau(0), 1)), host.played)
    }

    @Test
    fun `nothing happens while input is disabled`() {
        settled()
        robot.interact { view.inputEnabled = false }
        click(centre(tableau(2)))
        click(centre(stock))
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        assertEquals(listOf(0.0), translateOf(tableau(1)))
        assertTrue(outlines("sol-drop-ok").isEmpty())
        fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        assertTrue(host.played.isEmpty())
        assertEquals(0, host.stockClicks)
    }

    @Test
    fun `disabling input during a drag sends the cards back on release`() {
        settled()
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        robot.interact { view.inputEnabled = false }
        fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        assertTrue(host.played.isEmpty())
        assertEquals(listOf(0.0), translateOf(tableau(1)))
    }

    @Test
    fun `a hint outlines the cards and the target and goes after its delay or on show`() {
        settled()
        robot.interact { view.showHint(tableau(1), 1, tableau(0)) }
        assertEquals(listOf(rectOf(tableau(1)), rectOf(tableau(0))), outlines("sol-hint"))
        awaitNoOutline("sol-hint")
        robot.interact { view.showHint(tableau(1), 1, tableau(0)) }
        robot.interact { view.show(host.table) }
        assertTrue(outlines("sol-hint").isEmpty())
    }

    @Test
    fun `a hint that ends during a drag does not cancel it`() {
        settled()
        robot.interact { view.showHint(tableau(1), 1, tableau(0)) }
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        awaitNoOutline("sol-hint")
        assertNotEquals(listOf(0.0), translateOf(tableau(1)))
        fire(MouseEvent.MOUSE_RELEASED, slotCentre(tableau(0)))
        assertEquals(listOf<Move>(Move.Transfer(tableau(1), tableau(0), 1)), host.played)
    }

    @Test
    fun `the cursor outlines the top cards or the slot of an empty pile and keeps a drag`() {
        settled()
        robot.interact { view.showCursor(tableau(3), 2) }
        val cards =
            onFx {
                view
                    .layoutNow()
                    .cards
                    .filter { it.pile == tableau(3) }
                    .takeLast(2)
            }
        assertEquals(listOf(cards[0].rect.union(cards[1].rect)), outlines("sol-cursor"))
        robot.interact { view.showCursor(tableau(6), 1) }
        assertEquals(listOf(onFx { view.layoutNow().slots.getValue(tableau(6)) }), outlines("sol-cursor"))
        startDrag(centre(tableau(1)), slotCentre(tableau(0)))
        robot.interact { view.showCursor(null, 0) }
        assertTrue(outlines("sol-cursor").isEmpty())
        assertNotEquals(listOf(0.0), translateOf(tableau(1)))
    }

    @Test
    fun `the dark scheme draws the cards dark`() {
        settled()
        val light = onFx { pixels(view.nodesOf(tableau(0), 1).single().image) }
        robot.interact { view.scene.preferences.colorScheme = ColorScheme.DARK }
        settled()
        val dark = onFx { pixels(view.nodesOf(tableau(0), 1).single().image) }
        assertNotEquals(light, dark)
    }
}
