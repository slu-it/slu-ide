package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.Gender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KokoroVoicesTest {
    private val all = KokoroVoices.all

    @Test
    fun `has the 28 English voices with the default first`() {
        assertEquals(28, all.size)
        assertEquals(28, all.map { it.id }.toSet().size)
        assertEquals("af_heart", all.first().id)
        assertTrue(all.all { it.engineId == "kokoro" })
    }

    @Test
    fun `labels come from the id`() {
        val heart = all.first()
        assertEquals("Heart", heart.name)
        assertEquals("en-US", heart.language)
        assertEquals(Gender.FEMALE, heart.gender)
        assertEquals("A", heart.grade)
        val george = all.first { it.id == "bm_george" }
        assertEquals("George", george.name)
        assertEquals("en-GB", george.language)
        assertEquals(Gender.MALE, george.gender)
        assertEquals("C", george.grade)
    }

    @Test
    fun `American first, then British, each by grade and name`() {
        assertEquals(
            listOf(
                "af_heart",
                "af_bella",
                "af_nicole",
                "af_aoede",
                "am_fenrir",
                "af_kore",
                "am_michael",
                "am_puck",
                "af_sarah",
                "af_alloy",
                "af_nova",
                "af_sky",
                "am_echo",
                "am_eric",
                "af_jessica",
                "am_liam",
                "am_onyx",
                "af_river",
                "am_santa",
                "am_adam",
                "bf_emma",
                "bm_fable",
                "bm_george",
                "bf_isabella",
                "bm_lewis",
                "bf_alice",
                "bm_daniel",
                "bf_lily",
            ),
            all.map { it.id },
        )
    }
}
