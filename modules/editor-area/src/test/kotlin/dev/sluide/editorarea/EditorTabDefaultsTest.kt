package dev.sluide.editorarea

import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Group
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.ref.WeakReference
import kotlin.test.assertNull

class EditorTabDefaultsTest {
    private val tab =
        object : EditorTab {
            override val title = SimpleStringProperty("plain")
            override val tooltip = "plain"
            override val content = Group()
        }

    /**
     * The defaults are shared by every tab; a listener they kept (a tab header's, a menu binding's, `CloseRequests`')
     * would keep its window alive for good, e.g. after a window was dropped without closing its tabs.
     */
    @ParameterizedTest
    @ValueSource(strings = ["activity", "closableByShortcut", "closeRequested"])
    fun `a default keeps no listener`(name: String) {
        val default: ObservableValue<*> =
            when (name) {
                "activity" -> tab.activity
                "closableByShortcut" -> tab.closableByShortcut
                else -> tab.closeRequested
            }

        val captured = subscribeCapturing(default)

        // Poll with a deadline: one System.gc() is only a request.
        val deadline = System.nanoTime() + 5_000_000_000
        while (captured.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(10)
        }
        assertNull(captured.get())
    }

    /**
     * Subscribes an invalidation and a change listener that capture an object (tab headers and `CloseRequests` use
     * change listeners, bindings invalidation listeners), in a function of its own, so no local slot of the test keeps
     * the captured object reachable.
     */
    private fun subscribeCapturing(value: ObservableValue<*>): WeakReference<Any> {
        val captured = Any()
        value.subscribe(Runnable { captured.hashCode() })
        @Suppress("UNCHECKED_CAST")
        (value as ObservableValue<Any?>).subscribe { _ -> captured.hashCode() }
        return WeakReference(captured)
    }
}
