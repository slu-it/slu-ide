package dev.sluide.speech.ui

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceItemsTest {
    @Test
    fun `a voice label has the name, the gender and the grade`() {
        assertEquals("Heart · female · A", voiceLabel(HEART))
        assertEquals("Adam · male", voiceLabel(Voice("am_adam", "Adam", "en-US", Gender.MALE, null, "fake")))
    }

    @Test
    fun `the picker groups voices under a heading per language in their order`() {
        assertEquals(
            listOf(
                VoiceItem.Heading("American English"),
                VoiceItem.Entry(HEART),
                VoiceItem.Heading("British English"),
                VoiceItem.Entry(EMMA),
            ),
            voiceItems(listOf(HEART, EMMA)),
        )
    }
}
