package dev.sluide.translation.opusmt

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * What decoding an Opus-MT (Marian) model needs from its `config.json` and `generation_config.json`; a value of the
 * generation config wins. [maxLength] counts the decoder start token, as in Hugging Face.
 */
internal data class MarianConfig(
    val decoderStartId: Int,
    val eosId: Int,
    val badWordIds: Set<Int>,
    val forcedEosId: Int?,
    val maxLength: Int,
    val numBeams: Int,
    val lengthPenalty: Double,
    val renormalize: Boolean,
    val decoderLayers: Int,
    val heads: Int,
    val headDim: Int,
) {
    companion object {
        private val mapper = JsonMapper.builder().build()

        fun load(
            config: Path,
            generation: Path,
        ): MarianConfig = parse(Files.readString(config), Files.readString(generation))

        fun parse(
            config: String,
            generation: String,
        ): MarianConfig {
            val base = mapper.readTree(config)
            val gen = mapper.readTree(generation)

            fun node(name: String): JsonNode? =
                gen.get(name)?.takeUnless { it.isNull } ?: base.get(name)?.takeUnless { it.isNull }

            fun int(name: String): Int =
                node(name)?.asInt() ?: throw IllegalArgumentException("The model configuration has no $name")

            val pad = node("pad_token_id")?.asInt()
            val badWords =
                node("bad_words_ids")
                    ?.filter { it.isArray && it.size() == 1 }
                    ?.map { it[0].asInt() }
                    ?.toSet()
                    ?: setOfNotNull(pad)
            val model = int("d_model")
            val heads = int("decoder_attention_heads")
            require(heads > 0 && model % heads == 0) { "d_model $model is not divisible by $heads heads" }
            return MarianConfig(
                decoderStartId = node("decoder_start_token_id")?.asInt() ?: pad ?: int("decoder_start_token_id"),
                eosId = int("eos_token_id"),
                badWordIds = badWords,
                forcedEosId = node("forced_eos_token_id")?.asInt(),
                maxLength = node("max_length")?.asInt() ?: 512,
                numBeams = (node("num_beams")?.asInt() ?: 1).coerceAtLeast(1),
                lengthPenalty = node("length_penalty")?.asDouble() ?: 1.0,
                renormalize = node("renormalize_logits")?.asBoolean() ?: false,
                decoderLayers = int("decoder_layers"),
                heads = heads,
                headDim = model / heads,
            )
        }
    }
}
