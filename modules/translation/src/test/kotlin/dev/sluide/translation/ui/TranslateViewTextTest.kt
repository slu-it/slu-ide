package dev.sluide.translation.ui

import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.session.SetupProblem
import dev.sluide.translation.session.SourceChoice
import dev.sluide.translation.session.english
import dev.sluide.translation.session.german
import dev.sluide.translation.session.swedish
import kotlin.test.Test
import kotlin.test.assertEquals

/** The texts and the list filter of the Translate view, without a scene. */
class TranslateViewTextTest {
    private val names = mapOf("de" to "German", "en" to "English", "sv" to "Swedish")
    private val name: (String) -> String = { names.getValue(it) }

    private fun problem(
        viaEnglish: Boolean,
        vararg missing: PairModel,
        bytes: Long = 220_400_000,
        failure: String? = null,
    ) = SetupProblem(german, swedish, Quality.MEDIUM, missing.toList(), viaEnglish, bytes, null, failure)

    @Test
    fun `a direct pair that is missing is not installed`() {
        val text = noticeText(problem(false, PairModel("de", "sv", Quality.MEDIUM)), name)

        assertEquals("German → Swedish (Medium) is not installed", text)
    }

    @Test
    fun `a route through English names the models it needs`() {
        val both = problem(true, PairModel("de", "en", Quality.MEDIUM), PairModel("en", "sv", Quality.MEDIUM))
        val one = problem(true, PairModel("en", "sv", Quality.MEDIUM))

        assertEquals("German → Swedish (Medium) needs German → English and English → Swedish", noticeText(both, name))
        assertEquals("German → Swedish (Medium) needs English → Swedish", noticeText(one, name))
    }

    @Test
    fun `the download label has the size, or says again after a failure`() {
        assertEquals("Download (220 MB)", downloadLabel(problem(false)))
        assertEquals("Download again", downloadLabel(problem(false, failure = "no network")))
    }

    @Test
    fun `the list filter ignores case and blanks and keeps the order`() {
        val all = listOf("Danish", "German", "Hungarian", "Swedish").map { Choice(it, it) }

        assertEquals(listOf("German"), all.matching(" ger").map { it.value })
        assertEquals(listOf("Hungarian"), all.matching("HUNG").map { it.value })
        assertEquals(listOf("Danish", "Swedish"), all.matching("sh").map { it.value })
        assertEquals(listOf("Danish", "German", "Hungarian"), all.matching("an").map { it.value })
        assertEquals(all, all.matching("  "))
        assertEquals(emptyList(), all.matching("xyz"))
    }

    @Test
    fun `the source picker shows the detected language and the scratch file follows Markdown`() {
        assertEquals("Detect", sourceLabel(SourceChoice.Detect, null))
        assertEquals("Detect (German)", sourceLabel(SourceChoice.Detect, german))
        assertEquals("English", sourceLabel(SourceChoice.Picked(english), german))
        assertEquals("md", scratchExtension(true))
        assertEquals("txt", scratchExtension(false))
    }
}
