package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.Gender
import dev.sluide.speech.engine.Voice

/**
 * The English voices of Kokoro-82M with the grades its publisher gives them. American first, then British; within
 * each the best grade first, then by name. The first voice, `af_heart`, is the default.
 */
internal object KokoroVoices {
    const val ENGINE_ID = "kokoro"

    private val GRADES = listOf("A", "A-", "B+", "B", "B-", "C+", "C", "C-", "D+", "D", "D-", "F+", "F")

    private val GRADED =
        mapOf(
            "af_heart" to "A",
            "af_bella" to "A-",
            "af_nicole" to "B-",
            "af_aoede" to "C+",
            "af_kore" to "C+",
            "af_sarah" to "C+",
            "af_alloy" to "C",
            "af_nova" to "C",
            "af_sky" to "C-",
            "af_jessica" to "D",
            "af_river" to "D",
            "am_fenrir" to "C+",
            "am_michael" to "C+",
            "am_puck" to "C+",
            "am_echo" to "D",
            "am_eric" to "D",
            "am_liam" to "D",
            "am_onyx" to "D",
            "am_santa" to "D-",
            "am_adam" to "F+",
            "bf_emma" to "B-",
            "bf_isabella" to "C",
            "bm_george" to "C",
            "bm_fable" to "C",
            "bm_lewis" to "D+",
            "bf_alice" to "D",
            "bf_lily" to "D",
            "bm_daniel" to "D",
        )

    val all: List<Voice> =
        GRADED
            .map { (id, grade) ->
                Voice(
                    id = id,
                    name = id.substringAfter('_').replaceFirstChar { it.uppercase() },
                    language = if (id[0] == 'a') "en-US" else "en-GB",
                    gender = if (id[1] == 'f') Gender.FEMALE else Gender.MALE,
                    grade = grade,
                    engineId = ENGINE_ID,
                )
            }.sortedWith(
                compareBy<Voice>({ it.language != "en-US" }, { GRADES.indexOf(it.grade) }, { it.name }),
            )
}
