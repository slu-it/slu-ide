package dev.sluide.translation.opusmt

import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.engine.Route
import dev.sluide.translation.text.Placeholders
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
    }

    @Test
    fun `English to German`() {
        requireModels("en-de")
        val translation = translate("The house is small, but the garden is big.", Route.Direct("en-de"))
        assertTrue("haus" in translation.lowercase(), translation)
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

    /**
     * The placeholder form (`Placeholders.DEFAULT`) must survive every probed pair, also next to a link's text.
     * Chosen by this probe: `⟦1⟧` was dropped or repeated, `{1}` and `__1__` broke in Chinese, `(1)` became "punkt 1"
     * in Swedish.
     */
    @Test
    fun `the default placeholder form survives German, English, Swedish and Chinese`() {
        requireModels("de-en", "en-de", "en-sv", "en-zh")
        val form = Placeholders.DEFAULT

        // %1 ... %4 stand for the tokens; the shapes are the ones TranslationText makes, a link's text between two.
        fun sentence(template: String) = (1..4).fold(template) { text, n -> text.replace("%$n", form.token(n - 1)) }
        val english =
            listOf(
                "Install the package with %1, then open %2 and set %3 to true.",
                "The %1 function returns %2 when the file does not exist.",
                "%1 starts the server.",
                "See %1 for details.",
                "Install it with the %1Gradle plugin%2.",
                "See the %1docs%2 or write to %3.",
                "%1%2badge%3%4",
            ).map(::sentence)
        val german =
            listOf(
                "Führe %1 aus und öffne dann %2.",
                "%1 startet den Server.",
                "Lies zuerst den %1Benutzerleitfaden%2.",
            ).map(::sentence)
        val routes = listOf("en-de", "en-sv", "en-zh").map { Route.Direct(it) }
        val cases =
            routes.flatMap { route -> english.map { it to route } } +
                german.flatMap { listOf(it to Route.Direct("de-en"), it to Route.ViaEnglish("de-en", "en-zh")) }
        // What the user gets: every span back exactly once, at a token the model kept (not appended for a lost one).
        val spans = List(4) { "`s$it`" }
        val lost =
            cases.filter { (text, route) ->
                val count = (0..3).count { form.token(it) in text }
                val translation = translate(text, route)
                val restored = form.restore(translation, spans.take(count))
                form.occurrences(translation, 0, translation.length).size != count ||
                    spans.take(count).any { restored.split(it).size != 2 }
            }
        assertTrue(lost.isEmpty(), "Placeholders lost or doubled in: $lost")
    }

    @Test
    fun `a token that makes the model repeat stops at the length limit`() {
        requireModels("en-de")
        translate("Hello.", Route.Direct("en-de")) // loads the model, so only the decoding is timed
        val start = System.nanoTime()
        val translation = translate("Use ⟦1⟧ to start.", Route.Direct("en-de"))
        val millis = (System.nanoTime() - start) / 1_000_000
        // Without the limit this ran to 512 tokens of dashes in 12 s.
        assertTrue(translation.length < 100 && millis < 5_000, "$millis ms: $translation")
    }
}
