package dev.sluide.databases.session

import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionListenersTest {
    @Test
    fun `events reach the listeners in order until one is removed`() {
        val listeners = SessionListeners(1)
        val first = mutableListOf<SessionEvent>()
        val second = mutableListOf<SessionEvent>()
        val firstListener: (SessionEvent) -> Unit = { first += it }
        listeners.add(firstListener)
        listeners.add { second += it }

        listeners.emit(SessionEvent.RunningChanged(true))
        listeners.remove(firstListener)
        listeners.emit(SessionEvent.RunningChanged(false))

        assertEquals(listOf<SessionEvent>(SessionEvent.RunningChanged(true)), first)
        assertEquals(
            listOf<SessionEvent>(SessionEvent.RunningChanged(true), SessionEvent.RunningChanged(false)),
            second,
        )
    }

    @Test
    fun `Closed is the last event a listener gets`() {
        val listeners = SessionListeners(1)
        val seen = mutableListOf<SessionEvent>()
        listeners.add { seen += it }

        listeners.emit(SessionEvent.TransactionChanged(true))
        listeners.emit(SessionEvent.Closed("gone"))
        listeners.emit(SessionEvent.TransactionChanged(false))
        listeners.emit(SessionEvent.Closed(null))

        assertEquals(listOf<SessionEvent>(SessionEvent.TransactionChanged(true), SessionEvent.Closed("gone")), seen)
    }

    @Test
    fun `a listener that fails is logged by session number and event type only, the others still get the event`() {
        val listeners = SessionListeners(7)
        val seen = mutableListOf<SessionEvent>()
        listeners.add { throw IllegalStateException("secret-host.example") }
        listeners.add { seen += it }

        LogCapture(SessionListeners::class.java.name).use { log ->
            listeners.emit(SessionEvent.Closed("secret-host.example"))

            assertEquals(listOf<SessionEvent>(SessionEvent.Closed("secret-host.example")), seen)
            assertEquals(listOf(Level.SEVERE to "db#7 session listener failed on Closed"), log.lines.toList())
            assertTrue(assertIs<IllegalStateException>(log.thrown.single()).message == "secret-host.example")
        }
    }
}
