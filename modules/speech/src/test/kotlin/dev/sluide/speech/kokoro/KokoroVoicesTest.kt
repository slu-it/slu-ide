package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroVoicesTest {
    private val all = KokoroVoices.all

    @Test
    fun `offers one voice per accent and gender, the default first`() {
        assertEquals(
            listOf("af_heart", "am_michael", "bf_emma", "bm_george"),
            all.map { it.id },
        )
        assertTrue(all.all { it.engineId == "kokoro" })
    }

    @Test
    fun `the table is keyed by the language and gender of each voice`() {
        assertEquals(4, KokoroVoices.BY_ACCENT_AND_GENDER.size)
        KokoroVoices.BY_ACCENT_AND_GENDER.forEach { (key, voice) ->
            assertEquals(key, voice.language to voice.gender)
        }
    }

    @Test
    fun `labels and grades`() {
        val heart = all.first()
        assertEquals("Heart", heart.name)
        assertEquals("en-US", heart.language)
        assertEquals(Gender.FEMALE, heart.gender)
        assertEquals(
            listOf("A", "C+", "B-", "C"),
            all.map { it.grade },
        )
        val george = all.first { it.id == "bm_george" }
        assertEquals("George", george.name)
        assertEquals("en-GB", george.language)
        assertEquals(Gender.MALE, george.gender)
    }
}
