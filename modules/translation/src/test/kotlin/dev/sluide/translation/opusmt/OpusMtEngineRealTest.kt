package dev.sluide.translation.opusmt

import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test against the real Opus-MT models (ADR 0126), run by hand: `SLUIDE_OPUSMT_SMOKE=1` and the Medium models
 * of de-en, en-de and en-sv (en-zh for the Chinese case) in `~/.sluide/models/opus-mt` (download them in the app or by
 * the catalog's URLs); a test whose models are missing is skipped. Prints every translation with its time.
 */
@EnabledIfEnvironmentVariable(named = "SLUIDE_OPUSMT_SMOKE", matches = "1")
class OpusMtEngineRealTest {
    private val engine = OpusMtEngine()

    private fun requireModels(vararg pairs: String) {
        val missing = pairs.filterNot { engine.installed(PairModel(it.take(2), it.takeLast(2), Quality.MEDIUM)) }
        assumeTrue(missing.isEmpty(), "Opus-MT Medium models missing in ${engine.root}: $missing")
    }

    @AfterEach
    fun tearDown() {
        engine.close()
    }

    private fun translate(
        sentence: String,
        route: Route,
    ): String {
        val start = System.nanoTime()
        val translation = engine.translate(sentence, route, Quality.MEDIUM) { false }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("${route.pairs.joinToString(" → ")} ($millis ms): $sentence => $translation")
        return translation
    }

    @Test
    fun `German to English`() {
        requireModels("de-en")
        val translation = translate("Das Wetter ist heute sehr schön.", Route.Direct("de-en"))
        assertTrue("weather" in translation.lowercase(), translation)
        translate(
            "Ich habe gestern ein neues Buch gekauft, aber ich habe es noch nicht gelesen.",
            Route.Direct("de-en"),
        )
        translate("Nutze {1} zum Starten.", Route.Direct("de-en"))
        translate("Nutze __1__ zum Starten.", Route.Direct("de-en"))
        translate("Nutze ⟦1⟧ zum Starten.", Route.Direct("de-en"))
    }

    @Test
    fun `English to German`() {
        requireModels("en-de")
        val translation = translate("The house is small, but the garden is big.", Route.Direct("en-de"))
        assertTrue("haus" in translation.lowercase(), translation)
        translate("Use {1} to start.", Route.Direct("en-de"))
        translate("Use __1__ to start.", Route.Direct("en-de"))
        translate("Use ⟦1⟧ to start.", Route.Direct("en-de"))
    }

    @Test
    fun `German to Swedish through English`() {
        requireModels("de-en", "en-sv")
        val route = Route.ViaEnglish("de-en", "en-sv")
        val translation = translate("Ich trinke gern Kaffee am Morgen.", route)
        assertTrue("kaffe" in translation.lowercase(), translation)
        translate("Ich trinke gern Kaffee am Morgen.", route)
    }

    @Test
    fun `English to Chinese with the target-language token`() {
        requireModels("en-zh")
        val translation = translate("I like to drink tea in the morning.", Route.Direct("en-zh"))
        assertTrue(translation.isNotBlank() && translation.none { it in 'a'..'z' || it in 'A'..'Z' }, translation)
    }
}
