package dev.sluide.speech.kokoro

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class EspeakToKokoroTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "ʔˌn̩, tn",
        "ʔn̩, tn",
        "a^ɪ, I",
        "a^ʊ, W",
        "d^ʒ, ʤ",
        "e^ɪ, A",
        "e, A",
        "t^ʃ, ʧ",
        "ɔ^ɪ, Y",
        "ə^l, ᵊl",
        "ʲo, jɔ",
        "ʲə, jə",
        "bʲ, b",
        "ɚ, əɹ",
        "r, ɹ",
        "x, k",
        "ç, k",
        "ɐ, ə",
        "ɬ, l",
        "ã, a",
    )
    fun `each table entry`(
        ipa: String,
        expected: String,
    ) {
        assertEquals(expected, EspeakToKokoro.convert(ipa, british = false))
        assertEquals(expected, EspeakToKokoro.convert(ipa, british = true))
    }

    @ParameterizedTest(name = "american {0} -> {1}")
    @CsvSource("o^ʊ, O", "ɜːɹ, ɜɹ", "ɜːr, ɜɹ", "ɜː, ɜɹ", "ɪə, iə", "ɑː, ɑ", "o, ɔ", "ɾ, T", "ʔ, t", "a^b, ab")
    fun `american branch and final replacements`(
        ipa: String,
        expected: String,
    ) {
        assertEquals(expected, EspeakToKokoro.convert(ipa, british = false))
    }

    @ParameterizedTest(name = "british {0} -> {1}")
    @CsvSource("iə, ɪə", "ə^ʊ, Q", "ɜː, ɜː", "ɑː, ɑː", "ɪə, ɪə", "o^ʊ, ɔʊ", "o, ɔ", "ɾ, T", "ʔ, t")
    fun `british branch keeps the long mark`(
        ipa: String,
        expected: String,
    ) {
        assertEquals(expected, EspeakToKokoro.convert(ipa, british = true))
    }

    @Test
    fun `longest key goes first`() {
        // e^ɪ is one rule before e; otherwise it would become A^ɪ and then Aɪ
        assertEquals("A", EspeakToKokoro.convert("e^ɪ", british = false))
        assertEquals("Aʊ", EspeakToKokoro.convert("e^ʊ", british = false))
    }

    @Test
    fun `british e tie schwa is already A when the rule runs, as in misaki`() {
        // misaki applies the table (e -> A) before the British e^ə -> ɛː rule, so that rule never matches
        assertEquals("Aə", EspeakToKokoro.convert("e^ə", british = true))
    }

    @Test
    fun `syllabic mark moves before its consonant`() {
        assertEquals("bᵊn", EspeakToKokoro.convert("bn̩", british = false))
        assertEquals("ᵊn", EspeakToKokoro.convert("n̩", british = true))
    }

    @Test
    fun `syllabic mark without a character before it is deleted`() {
        assertEquals("a b", EspeakToKokoro.convert("a ̩b", british = false))
    }

    @Test
    fun `real espeak output`() {
        assertEquals("həlˈO", EspeakToKokoro.convert("həlˈo^ʊ", british = false))
        assertEquals("həlˈQ", EspeakToKokoro.convert("həlˈə^ʊ", british = true))
        assertEquals("wˈɜɹld", EspeakToKokoro.convert("wˈɜːld", british = false))
        assertEquals("ɹˈidɪŋ", EspeakToKokoro.convert("ɹˈiːdɪŋ", british = false))
    }
}
