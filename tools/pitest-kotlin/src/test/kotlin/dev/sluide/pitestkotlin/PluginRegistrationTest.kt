package dev.sluide.pitestkotlin

import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.build.InterceptorType
import org.pitest.mutationtest.build.MutationInterceptorFactory
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** pitest finds its interceptor factories with the ServiceLoader, as here, and switches them on by their feature. */
class PluginRegistrationTest {
    private val ours =
        ServiceLoader
            .load(MutationInterceptorFactory::class.java)
            .filter { it.javaClass.packageName == "dev.sluide.pitestkotlin" }
            // pitest keeps feature names in lower case and matches them ignoring case.
            .associateBy { it.provides().name().uppercase() }

    @Test
    fun `KOTLIN_JUNK is registered, on by default, and filters`() {
        val factory = assertNotNull(ours["KOTLIN_JUNK"])
        val interceptor = factory.createInterceptor(InterceptorParameters(null, null, null, null, null, null, null))

        assertEquals(true, factory.provides().isOnByDefault)
        assertIs<KotlinJunkFilter>(interceptor)
        assertEquals(InterceptorType.FILTER, interceptor.type())
    }

    @Test
    fun `LINES is registered, off by default, with its file parameter`() {
        val feature = assertNotNull(ours["LINES"]).provides()

        assertEquals(false, feature.isOnByDefault)
        assertEquals(listOf("file"), feature.params().map { it.name() })
    }
}
