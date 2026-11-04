package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.engine.SetupProblem
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import dev.sluide.speech.text.SpeechText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReadAloudModelTest {
    private val host = FakeHost()
    private val model = ReadAloudModel(host)
    private var changes = 0

    init {
        model.onChange = { changes++ }
    }

    private val text = "One. Two. Three."

    private fun position(
        chunk: Int,
        start: Int,
        end: Int,
        word: IntRange? = null,
    ) = ReadingPosition(chunk, start, end, word?.first, word?.last?.plus(1))

    private fun speaking(
        text: String = this.text,
        markdown: Boolean = false,
    ) {
        model.read(text, markdown)
        host.reading.emit(ReadingState.PREPARING)
        host.reading.emit(ReadingState.SPEAKING)
    }

    @Test
    fun `read puts the text in and starts at the first sentence at once`() {
        model.read(text, markdown = false)

        assertEquals(text, model.text)
        assertEquals(0, host.reading.startChunk)
        assertEquals(listOf("start"), host.reading.calls)
        assertEquals("Preparing…", model.ui.status)
        assertFalse(model.ui.editable)
        assertEquals(ViewActivity.RUNNING, model.ui.activity)
        assertEquals(1, model.ui.textVersion)
    }

    @Test
    fun `read with setup problems shows the notice and starts nothing`() {
        host.problems = listOf(MODEL_MISSING)

        model.read(text, markdown = false)

        assertEquals(text, model.text)
        assertTrue(host.readings.isEmpty())
        assertEquals(listOf<SetupProblem>(MODEL_MISSING), model.ui.notice)
        assertTrue(model.ui.editable)
        assertEquals(PlayMode.PLAY, model.ui.playMode)
        assertEquals("", model.ui.status)
    }

    @Test
    fun `read stops a running reading first and a later check answer is dropped`() {
        speaking()
        val first = host.reading
        host.holdChecks = true

        model.read("Other.", markdown = false)
        model.read("Another.", markdown = false)
        host.held.forEach { it(emptyList()) }

        assertTrue("stop" in first.calls)
        assertEquals(2, host.readings.size, "only the last read starts a reading")
        assertEquals("Another.", model.text)
    }

    @Test
    fun `play starts at the sentence of the caret`() {
        model.textEdited(text)

        model.play(caret = 6)

        assertEquals(1, host.reading.startChunk)
    }

    @Test
    fun `play from the end or past the last sentence starts at the first`() {
        model.textEdited(text)

        model.play(caret = text.length)

        assertEquals(0, host.reading.startChunk)
    }

    @Test
    fun `play finds the sentence in Markdown by shown offsets`() {
        val markdown = "# Title\n\nHello **world**. Bye."
        model.read(markdown, markdown = true)
        host.reading.emit(ReadingState.STOPPED)

        model.play(caret = markdown.indexOf("Bye"))

        assertEquals(2, host.reading.startChunk, "the heading is the first chunk")
    }

    @Test
    fun `play does nothing for an empty or blank text`() {
        model.textEdited("  \n ")
        assertFalse(model.ui.playEnabled)

        model.play(0)

        assertTrue(host.readings.isEmpty())
        model.textEdited("Hi.")
        assertTrue(model.ui.playEnabled)
    }

    @Test
    fun `speaking shows the sentence number and the pause button, a pause shows Paused and resume`() {
        speaking()
        host.reading.position = position(1, 5, 9)
        host.pollTick?.run()

        assertEquals("Sentence 2 of 3", model.ui.status)
        assertEquals(PlayMode.PAUSE, model.ui.playMode)
        assertTrue(model.ui.stopEnabled)

        model.togglePause()
        host.reading.emit(ReadingState.PAUSED)

        assertEquals("Paused", model.ui.status)
        assertEquals(PlayMode.RESUME, model.ui.playMode)
        assertEquals(ViewActivity.RUNNING, model.ui.activity)
        assertFalse(model.ui.editable)

        model.togglePause()
        host.reading.emit(ReadingState.SPEAKING)

        assertEquals(listOf("start", "pause", "resume"), host.reading.calls)
        assertEquals(PlayMode.PAUSE, model.ui.playMode)
    }

    @Test
    fun `a pause by the coordinator shows Paused`() {
        speaking()

        host.reading.emit(ReadingState.PAUSED)

        assertEquals("Paused", model.ui.status)
        assertEquals(PlayMode.RESUME, model.ui.playMode)
    }

    @Test
    fun `stop ends the reading, clears the highlight and unlocks the text`() {
        speaking()
        host.reading.position = position(0, 0, 4, 0..2)
        host.pollTick?.run()
        assertTrue(model.ui.overlay.isNotEmpty())

        model.stop()

        assertTrue("stop" in host.reading.calls)
        assertTrue(model.ui.editable)
        assertEquals(emptyList(), model.ui.overlay)
        assertNull(model.ui.reveal)
        assertEquals("", model.ui.status)
        assertEquals(ViewActivity.NONE, model.ui.activity)
        assertFalse(model.ui.stopEnabled)
        assertEquals(1, host.pollClosed)
        // The stopped session's late report changes nothing.
        host.reading.emit(ReadingState.STOPPED)
        assertEquals("", model.ui.status)
    }

    @Test
    fun `the overlay holds the sentence and the word in shown offsets`() {
        speaking()
        host.reading.position = position(1, 5, 9, 5..7)

        host.pollTick?.run()

        assertEquals(
            listOf(StyledRange(5, 9, "speech-sentence"), StyledRange(5, 8, "speech-word")),
            model.ui.overlay,
        )
        assertEquals(5..7, model.ui.reveal)
    }

    @Test
    fun `between words the overlay holds the sentence and reveal follows it`() {
        speaking()
        host.reading.position = position(1, 5, 9)

        host.pollTick?.run()

        assertEquals(listOf(StyledRange(5, 9, "speech-sentence")), model.ui.overlay)
        assertEquals(5..8, model.ui.reveal)
    }

    @Test
    fun `the overlay of a Markdown text maps to the shown text`() {
        val markdown = "# Title\n\nHello **world**. Bye."
        speaking(markdown, markdown = true)
        val spoken = SpeechText.prepare(markdown, markdown = true).text
        val word = spoken.indexOf("world")
        host.reading.position = position(1, spoken.indexOf("Hello"), spoken.indexOf(". Bye") + 1, word..word + 4)

        host.pollTick?.run()

        val world = markdown.indexOf("world")
        assertEquals(
            listOf(
                StyledRange(markdown.indexOf("Hello"), markdown.indexOf(". Bye") + 1, "speech-sentence"),
                StyledRange(world, world + 5, "speech-word"),
            ),
            model.ui.overlay,
        )
    }

    @Test
    fun `an unchanged position publishes nothing`() {
        speaking()
        host.reading.position = position(1, 5, 9, 5..7)
        host.pollTick?.run()
        val before = changes

        host.pollTick?.run()

        assertEquals(before, changes)
    }

    @Test
    fun `changing the voice restarts at the current sentence and saves`() {
        speaking()
        host.reading.position = position(2, 10, 16)
        host.pollTick?.run()
        val old = host.reading

        model.setVoice(EMMA)

        assertTrue("stop" in old.calls)
        assertEquals(2, host.readings.size)
        assertEquals(EMMA, host.reading.voice)
        assertEquals(2, host.reading.startChunk)
        assertEquals("bf_emma" to 1.0, host.saved.last())
        assertEquals("Preparing…", model.ui.status)
        // The old session's late events do not count.
        old.emit(ReadingState.STOPPED)
        assertEquals("Preparing…", model.ui.status)
    }

    @Test
    fun `changing the speed restarts at the current sentence and saves`() {
        speaking()

        model.setSpeed(1.5)

        assertEquals(1.5, host.reading.speed)
        assertEquals(0, host.reading.startChunk)
        assertEquals("af_heart" to 1.5, host.saved.last())
    }

    @Test
    fun `changing voice or speed while idle only saves`() {
        model.setSpeed(2.0)
        model.setVoice(EMMA)

        assertTrue(host.readings.isEmpty())
        assertEquals(listOf("af_heart" to 2.0, "bf_emma" to 2.0), host.saved)
        assertEquals(EMMA, model.ui.voice)
    }

    @Test
    fun `jumpTo restarts at the sentence under the offset`() {
        speaking()

        model.jumpTo(11)

        assertEquals(2, host.reading.startChunk)
        assertEquals(2, host.readings.size)
    }

    @Test
    fun `the first voice and the saved speed are the start values, a saved voice id wins when known`() {
        assertEquals(HEART, model.ui.voice)
        val saved = ReadAloudModel(FakeHost(preferredVoiceId = "bf_emma", preferredSpeed = 1.25))
        assertEquals(EMMA, saved.ui.voice)
        assertEquals(1.25, saved.ui.speed)
        assertEquals(HEART, ReadAloudModel(FakeHost(preferredVoiceId = "gone")).ui.voice)
    }

    @Test
    fun `the coordinator is told when the reading plays and when it is over`() {
        speaking()
        assertEquals(listOf("started"), host.events)

        host.reading.emit(ReadingState.FINISHED)

        assertEquals(listOf("started", "disposed"), host.events)
    }

    @Test
    fun `finished unlocks the text and clears the overlay`() {
        speaking()
        host.reading.position = position(0, 0, 4)
        host.pollTick?.run()

        host.reading.emit(ReadingState.FINISHED)

        assertEquals("Finished", model.ui.status)
        assertTrue(model.ui.editable)
        assertEquals(emptyList(), model.ui.overlay)
        assertEquals(PlayMode.PLAY, model.ui.playMode)
        assertEquals(ViewActivity.NONE, model.ui.activity)
    }

    @Test
    fun `failed shows the error, unlocks and editing clears it`() {
        speaking()

        host.reading.emit(ReadingState.FAILED, "The model is broken.")

        assertEquals("The model is broken.", model.ui.status)
        assertTrue(model.ui.editable)
        model.textEdited("New text.")
        assertEquals("", model.ui.status)
    }

    @Test
    fun `a text without anything to read fails with a message`() {
        model.read("```\ncode\n```", markdown = true)

        assertTrue(host.readings.isEmpty())
        assertEquals("Nothing to read.", model.ui.status)
    }

    @Test
    fun `typing is taken over only while editable`() {
        model.textEdited("Typed.")
        assertEquals("Typed.", model.text)
        assertEquals(0, model.ui.textVersion)

        model.play(0)
        model.textEdited("Ignored.")

        assertEquals("Typed.", model.text)
    }

    @Test
    fun `checkAgain replaces the notice and starts nothing`() {
        host.problems = listOf(MODEL_MISSING)
        model.read(text, markdown = false)

        host.problems = emptyList()
        model.checkAgain()

        assertEquals(emptyList(), model.ui.notice)
        assertTrue(host.readings.isEmpty())
    }

    @Test
    fun `download and cancel go to the host for the missing model`() {
        host.problems = listOf(MODEL_MISSING)
        model.read(text, markdown = false)

        model.download()
        model.cancelDownload()

        assertEquals(listOf("download FP32", "cancel FP32"), host.events)
        assertSame(host.state, model.installState())
    }

    @Test
    fun `download without a missing model does nothing`() {
        model.download()

        assertEquals(emptyList(), host.events)
        assertNull(model.installState())
    }

    @Test
    fun `Check Again while a start waits for its check does not drop the start`() {
        host.holdChecks = true
        model.read(text, markdown = false)

        model.checkAgain()
        host.held.forEach { it(emptyList()) }

        assertEquals(1, host.readings.size)
        assertEquals("Preparing…", model.ui.status)
    }

    @Test
    fun `an offset at the end of a sentence belongs to that sentence`() {
        speaking()

        model.jumpTo(4)

        assertEquals(0, host.reading.startChunk)
    }

    @Test
    fun `a restart while paused stays paused`() {
        speaking()
        host.reading.emit(ReadingState.PAUSED)

        model.setSpeed(1.5)

        assertEquals(listOf("start", "pause"), host.reading.calls)
        host.reading.emit(ReadingState.PAUSED)
        assertEquals("Paused", model.ui.status)
    }

    @Test
    fun `choosing the voice or speed already selected does not restart`() {
        speaking()

        model.setVoice(HEART)
        model.setSpeed(1.0)

        assertEquals(1, host.readings.size)
        assertEquals(emptyList(), host.saved)
    }

    @Test
    fun `typing makes the text plain again`() {
        model.read("# Hi there. Bye", markdown = true)
        host.reading.emit(ReadingState.STOPPED)
        model.textEdited("# Hi there. Bye!")

        model.play(0)

        assertEquals("# Hi there. Bye!", host.reading.chunks.joinToString(" ") { it.text })
    }
}
