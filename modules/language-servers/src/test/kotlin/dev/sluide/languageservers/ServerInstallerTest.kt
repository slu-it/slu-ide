package dev.sluide.languageservers

import dev.sluide.backgroundtasks.BackgroundTasks
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What an installer that says nothing more than it must does. */
class ServerInstallerTest {
    private val minimal =
        object : ServerInstaller {
            override val availability: ObservableValue<ServerAvailability> =
                SimpleObjectProperty(ServerAvailability.Ready)

            override fun install(tasks: BackgroundTasks?) = Unit

            override fun cancel() = Unit
        }

    @Test
    fun `an installer names no tool by default`() {
        assertNull(minimal.toolName)
    }

    @Test
    fun `an installer whose command never changes never calls the listener, and closing its result does nothing`() {
        var calls = 0

        val registration = minimal.onCommandChanged { calls++ }
        registration.close()
        registration.close()

        assertEquals(0, calls)
    }
}
