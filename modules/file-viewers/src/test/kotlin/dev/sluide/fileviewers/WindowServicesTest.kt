package dev.sluide.fileviewers

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class WindowServicesTest {
    @Test
    fun `a registered service is found by its type`() {
        val services = WindowServices()
        val builder = StringBuilder("a")
        services.register(StringBuilder::class.java, builder)

        assertSame(builder, services.get(StringBuilder::class.java))
    }

    @Test
    fun `only the exact type matches`() {
        val services = WindowServices()
        services.register(StringBuilder::class.java, StringBuilder())

        assertNull(services.get(CharSequence::class.java))
        assertNull(services.get(Any::class.java))
        assertNull(services.get(Thread::class.java))
    }

    @Test
    fun `a type can be registered once`() {
        val services = WindowServices()
        services.register(StringBuilder::class.java, StringBuilder())

        assertFailsWith<IllegalArgumentException> { services.register(StringBuilder::class.java, StringBuilder()) }
    }

    @Test
    fun `services of different types coexist`() {
        val services = WindowServices()
        val builder = StringBuilder()
        val text = "text"
        services.register(StringBuilder::class.java, builder)
        services.register(String::class.java, text)

        assertSame(builder, services.get(StringBuilder::class.java))
        assertSame(text, services.get(String::class.java))
    }
}
