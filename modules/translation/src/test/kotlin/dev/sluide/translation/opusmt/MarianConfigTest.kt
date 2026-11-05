package dev.sluide.translation.opusmt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarianConfigTest {
    /** Shaped like the `config.json` of an Opus-MT export (fields that do not matter here left out). */
    private fun config(
        dModel: Int = 512,
        heads: Int = 8,
        extra: String = """
            "decoder_start_token_id": 58100,
            "bad_words_ids": [[58100]],
            "num_beams": 4,
            "max_length": 512,
        """,
    ) = """
        {
          "_name_or_path": "Helsinki-NLP/opus-mt-de-en",
          "activation_function": "swish",
          "architectures": ["MarianMTModel"],
          "d_model": $dModel,
          "decoder_attention_heads": $heads,
          "decoder_layers": 6,
          "encoder_attention_heads": 8,
          "encoder_layers": 6,
          "vocab_size": 58101,
          "pad_token_id": 58100,
          "eos_token_id": 0,
          $extra
          "model_type": "marian"
        }
    """

    private val generation = """
        {
          "bad_words_ids": [[58100]],
          "bos_token_id": 0,
          "decoder_start_token_id": 58100,
          "eos_token_id": 0,
          "forced_eos_token_id": 0,
          "max_length": 400,
          "num_beams": 6,
          "pad_token_id": 58100,
          "renormalize_logits": true
        }
    """

    @Test
    fun `reads the model's decoding settings, the generation config winning`() {
        val parsed = MarianConfig.parse(config(), generation)
        assertEquals(
            MarianConfig(
                decoderStartId = 58100,
                eosId = 0,
                badWordIds = setOf(58100),
                forcedEosId = 0,
                maxLength = 400,
                numBeams = 6,
                lengthPenalty = 1.0,
                renormalize = true,
                decoderLayers = 6,
                heads = 8,
                headDim = 64,
            ),
            parsed,
        )
    }

    @Test
    fun `the start token and the bad words fall back to the pad token, the rest to Hugging Face's defaults`() {
        val parsed = MarianConfig.parse(config(extra = ""), "{}")
        assertEquals(58100, parsed.decoderStartId)
        assertEquals(setOf(58100), parsed.badWordIds)
        assertEquals(1, parsed.numBeams)
        assertEquals(512, parsed.maxLength)
        assertEquals(1.0, parsed.lengthPenalty)
        assertNull(parsed.forcedEosId)
        assertEquals(false, parsed.renormalize)
    }

    @Test
    fun `a null in the generation config does not hide the model config's value`() {
        val parsed = MarianConfig.parse(config(), """{"num_beams": null, "length_penalty": 0.6}""")
        assertEquals(4, parsed.numBeams)
        assertEquals(0.6, parsed.lengthPenalty)
    }

    @Test
    fun `the head size is the model width over the heads`() {
        assertEquals(32, MarianConfig.parse(config(dModel = 256), generation).headDim)
        val error = assertFailsWith<IllegalArgumentException> { MarianConfig.parse(config(dModel = 500), generation) }
        assertTrue("500" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the length limit is three times the source, at least 16 tokens, plus the start token, within max_length`() {
        val parsed = MarianConfig.parse(config(), generation)
        assertEquals(17, parsed.maxLengthFor(1))
        assertEquals(17, parsed.maxLengthFor(5))
        assertEquals(31, parsed.maxLengthFor(10))
        assertEquals(400, parsed.maxLengthFor(133))
        assertEquals(400, parsed.maxLengthFor(400))
    }
}
