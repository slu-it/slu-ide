package dev.sluide.speech.engine

import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpeechEnginesTest {
    private val heart = voice("af_heart", "first")
    private val bella = voice("af_bella", "first")
    private val emma = voice("bf_emma", "second")
    private val first = FakeEngine("first", listOf(heart, bella))
    private val second = FakeEngine("second", listOf(emma))

    @Test
    fun `lists all voices in engine order`() {
        assertEquals(listOf(heart, bella, emma), SpeechEngines(listOf(first, second)).voices)
        assertEquals(listOf(emma, heart, bella), SpeechEngines(listOf(second, first)).voices)
    }

    @Test
    fun `finds the engine of a voice by its engine id`() {
        val engines = SpeechEngines(listOf(first, second))

        assertSame(first, engines.engineFor(bella))
        assertSame(second, engines.engineFor(emma))
    }

    @Test
    fun `refuses a voice of an unknown engine`() {
        val error = assertFailsWith<IllegalArgumentException> { SpeechEngines(listOf(first)).engineFor(emma) }

        assertEquals("No speech engine 'second' for voice 'bf_emma'", error.message)
    }

    @Test
    fun `works without any engine`() {
        val engines = SpeechEngines(emptyList())

        assertTrue(engines.voices.isEmpty())
        assertFailsWith<IllegalArgumentException> { engines.engineFor(heart) }
    }

    @Test
    fun `starts in a Spring context without any engine bean`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(SpeechEngines::class.java)
            context.refresh()

            assertTrue(context.getBean(SpeechEngines::class.java).voices.isEmpty())
        }
    }

    @Test
    fun `takes the engine beans of a Spring context`() {
        AnnotationConfigApplicationContext().use { context ->
            context.registerBean("first", SpeechEngine::class.java, Supplier { first })
            context.register(SpeechEngines::class.java)
            context.refresh()

            assertEquals(listOf(heart, bella), context.getBean(SpeechEngines::class.java).voices)
        }
    }

    private fun voice(
        id: String,
        engineId: String,
    ) = Voice(id, id.substringAfter('_'), "en-US", Gender.FEMALE, "A", engineId)

    private class FakeEngine(
        override val id: String,
        override val voices: List<Voice>,
    ) : SpeechEngine {
        override fun problems(): List<SetupProblem> = emptyList()

        override fun synthesize(
            chunk: SpeechChunk,
            voice: Voice,
            speed: Double,
        ): SynthesizedChunk = SynthesizedChunk(chunk, FloatArray(0), SAMPLE_RATE, emptyList())
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}
