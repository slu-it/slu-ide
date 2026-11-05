package dev.sluide.speech.ui

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceChoiceTest {
    private val michael = Voice("am_michael", "Michael", "en-US", Gender.MALE, "C+", "fake")
    private val george = Voice("bm_george", "George", "en-GB", Gender.MALE, "C", "fake")
    private val voices = listOf(HEART, michael, EMMA, george)

    @Test
    fun `accents are the distinct languages in order`() {
        assertEquals(listOf("en-US", "en-GB"), VoiceChoice.accents(voices))
    }

    @Test
    fun `genders are those of the language`() {
        assertEquals(listOf(Gender.FEMALE, Gender.MALE), VoiceChoice.genders(voices, "en-GB"))
        assertEquals(listOf(Gender.FEMALE), VoiceChoice.genders(listOf(HEART, EMMA), "en-GB"))
        assertEquals(emptyList(), VoiceChoice.genders(voices, "de-DE"))
    }

    @Test
    fun `the voice is the first of the accent and gender`() {
        assertEquals(george, VoiceChoice.voiceFor(voices, "en-GB", Gender.MALE))
        assertEquals(EMMA, VoiceChoice.voiceFor(voices, "en-GB", Gender.FEMALE))
        assertEquals(HEART, VoiceChoice.voiceFor(listOf(HEART, EMMA, george), "en-US", Gender.MALE))
        assertNull(VoiceChoice.voiceFor(voices, "de-DE", Gender.FEMALE))
    }

    @Test
    fun `names of accents and genders`() {
        assertEquals("American English", VoiceChoice.accentName("en-US"))
        assertEquals("British English", VoiceChoice.accentName("en-GB"))
        assertEquals("fr-FR", VoiceChoice.accentName("fr-FR"))
        assertEquals("Female", VoiceChoice.genderName(Gender.FEMALE))
        assertEquals("Male", VoiceChoice.genderName(Gender.MALE))
    }
}
