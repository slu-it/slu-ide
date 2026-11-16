package dev.sluide.fileviewers

import javafx.beans.value.ObservableValue
import javafx.scene.Group
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.ref.WeakReference
import kotlin.test.assertNull

class FileViewDefaultsTest {
    private val view =
        object : FileView {
            override val node = Group()

            override fun reload() {}
        }

    /**
     * The defaults are shared by every view; a listener they kept would keep what it captures (a tab header, a window)
     * alive for good, e.g. after a window was dropped without unsubscribing.
     */
    @ParameterizedTest
    @ValueSource(strings = ["activity", "readOnlyMarked"])
    fun `a default keeps no listener`(name: String) {
        val default: ObservableValue<*> =
            when (name) {
                "activity" -> view.activity
                else -> view.readOnlyMarked
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
