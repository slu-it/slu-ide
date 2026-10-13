package dev.sluide.ide

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Scope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class WindowScopeTest {
    class Log {
        val closed = mutableListOf<String>()
    }

    @Scope(WINDOW_SCOPE)
    class Tabs(
        private val log: Log,
    ) : AutoCloseable {
        override fun close() {
            log.closed += "tabs"
        }
    }

    @Scope(WINDOW_SCOPE)
    class Window(
        val tabs: Tabs,
        private val log: Log,
    ) : AutoCloseable {
        override fun close() {
            log.closed += "window"
        }
    }

    private fun context() =
        AnnotationConfigApplicationContext(
            WindowScopeConfiguration::class.java,
            Log::class.java,
            Tabs::class.java,
            Window::class.java,
        )

    private val AnnotationConfigApplicationContext.scope
        get() = beanFactory.getRegisteredScope(WINDOW_SCOPE) as WindowScope

    @Test
    fun `each window has its own beans, shared within the window`() {
        context().use { context ->
            val first = WindowBeans()
            val second = WindowBeans()
            val a = context.scope.inWindow(first) { context.getBean(Window::class.java) }
            val b = context.scope.inWindow(second) { context.getBean(Window::class.java) }

            assertNotSame(a, b)
            assertNotSame(a.tabs, b.tabs)
            assertSame(a, context.scope.inWindow(first) { context.getBean(Window::class.java) })
            assertSame(a.tabs, context.scope.inWindow(first) { context.getBean(Tabs::class.java) })
        }
    }

    @Test
    fun `destroying a window closes its beans, dependents first, and nothing else`() {
        context().use { context ->
            val first = WindowBeans()
            val second = WindowBeans()
            context.scope.inWindow(first) { context.getBean(Window::class.java) }
            context.scope.inWindow(second) { context.getBean(Window::class.java) }
            val log = context.getBean(Log::class.java)

            context.scope.destroy(first)

            assertEquals(listOf("window", "tabs"), log.closed)
            context.scope.destroy(first)
            assertEquals(2, log.closed.size)
        }
    }

    @Test
    fun `window beans cannot be used outside a window`() {
        context().use { context ->
            assertFailsWith<Exception> { context.getBean(Window::class.java) }
        }
    }
}
