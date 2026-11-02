package dev.sluide.databases.ui.cards

import dev.sluide.databases.ui.ConnectionStatus
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the connection cards: where a drag lands and what a status shows. */
class CardLogicTest {
    // The other cards' centres, as with a dragged card of a list of four.
    private val mids = listOf(20.0, 60.0, 100.0)

    @Test
    fun `dropIndex counts the other cards above the pointer`() {
        assertEquals(0, dropIndex(mids, 5.0), "top")
        assertEquals(1, dropIndex(mids, 40.0), "between")
        assertEquals(2, dropIndex(mids, 80.0), "between")
        assertEquals(3, dropIndex(mids, 500.0), "bottom")
        assertEquals(0, dropIndex(emptyList(), 10.0), "empty list")
    }

    @Test
    fun `a connected card's dot is green, orange with an open transaction`() {
        assertEquals(Dot.CONNECTED, cardState(ConnectionStatus.CONNECTED, false).dot)
        assertEquals(Dot.PENDING, cardState(ConnectionStatus.CONNECTED, true).dot)
        assertEquals(Dot.DISCONNECTED, cardState(ConnectionStatus.DISCONNECTED, true).dot)
    }

    @ParameterizedTest
    @EnumSource(ConnectionStatus::class)
    fun `status decides the dot and which buttons work`(status: ConnectionStatus) {
        val state = cardState(status, false)
        assertEquals(status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR, state.canConnect)
        assertEquals(status == ConnectionStatus.CONNECTED, state.canDisconnect)
        assertEquals(status == ConnectionStatus.CONNECTING, state.connecting)
        if (state.connecting) assertNull(state.dot) else assertTrue(state.dot != null)
        if (status == ConnectionStatus.ERROR) assertEquals(Dot.ERROR, state.dot)
    }
}
