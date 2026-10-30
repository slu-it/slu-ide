package dev.sluide.converting.model

import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.data.DataFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ConvertingModelTest {
    private val data =
        FakeFamily(
            "Data",
            listOf(FakeFormat("json"), FakeFormat("yaml"), FakeFormat("toml")),
            rejecting = setOf("toml"),
            readOptions = mapOf("yaml" to listOf(trim, upper)),
            writeOptions = mapOf("json" to listOf(upper, indent)),
        )
    private val requests = FakeFamily("Requests", listOf(FakeFormat("curl"), FakeFormat("http")))
    private val json = data.format("json")
    private val yaml = data.format("yaml")
    private val toml = data.format("toml")
    private val scheduler = ManualScheduler()
    private val dispatched = ArrayDeque<Runnable>()
    private var changes = 0
    private val model = model(listOf(data, requests))

    private fun model(families: List<FormatFamily<*>>) =
        ConvertingModel(families, dispatched::addLast, DELAY, scheduler).also { it.onChange = { changes++ } }

    /** Runs the scheduled tasks and the dispatched results until nothing is left. */
    private fun ConvertingModel.settled(): ConvertingModel {
        while (true) {
            val task = scheduler.waiting.firstOrNull()
            if (task != null) {
                scheduler.run(task)
            } else {
                (dispatched.removeFirstOrNull() ?: return this).run()
            }
        }
    }

    @Test
    fun `starts detecting on both sides with the first format on the right`() {
        assertEquals(Side.LEFT, model.source)
        assertEquals(Pick.Detect, model.pick(Side.LEFT))
        assertEquals(Pick.Detect, model.pick(Side.RIGHT))
        assertNull(model.format(Side.LEFT))
        assertEquals(json, model.format(Side.RIGHT))
        assertEquals("converted.json", model.fileName(Side.RIGHT))
    }

    @Test
    fun `several edits convert once after the debounce`() {
        model.edit(Side.LEFT, "yaml:a")
        model.edit(Side.LEFT, "yaml:ab")
        model.edit(Side.LEFT, "yaml:abc")

        assertEquals(listOf(DELAY), scheduler.waiting.map { it.delay })
        assertEquals("", model.text(Side.RIGHT))
        model.settled()
        assertEquals(1, data.reads)
        assertEquals("json:abc", model.text(Side.RIGHT))
        assertEquals(yaml, model.format(Side.LEFT))
    }

    @Test
    fun `a conversion finishing after a newer one is dropped`() {
        model.edit(Side.LEFT, "yaml:old")
        scheduler.run(scheduler.waiting.single())
        model.edit(Side.LEFT, "yaml:new")
        scheduler.run(scheduler.waiting.single())

        dispatched.removeLast().run()
        dispatched.removeLast().run()

        assertEquals("json:new", model.text(Side.RIGHT))
    }

    @Test
    fun `the model's own text on the target is no edit`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.edit(Side.RIGHT, "json:a")

        assertEquals(Side.LEFT, model.source)
        assertEquals(emptyList<ManualScheduler.Task>(), scheduler.waiting)
    }

    @Test
    fun `editing the target makes it the source, detected unless pinned`() {
        model.edit(Side.LEFT, "json:a")
        model.settled()
        assertEquals(Pick.Detect, model.pick(Side.RIGHT))
        assertEquals("yaml:a", model.text(Side.RIGHT))

        model.edit(Side.RIGHT, "toml:b")
        model.settled()

        assertEquals(Side.RIGHT, model.source)
        assertEquals(Status.Info("TOML · detected", 6, emptyList()), model.status(Side.RIGHT))
        assertEquals("json:b", model.text(Side.LEFT))
        assertEquals(Pick.Detect, model.pick(Side.LEFT))
    }

    @Test
    fun `a target the user pinned stays pinned as the source`() {
        model.choose(Side.RIGHT, Pick.Pinned(json))
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.edit(Side.RIGHT, "toml:b")
        model.settled()

        assertEquals(Status.Failure("not JSON", null), model.status(Side.RIGHT))
        assertEquals("Looks like TOML", model.hint?.message)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("steps")
    fun `every change calls onChange once`(
        @Suppress("unused") name: String,
        step: (ConvertingModelTest) -> Unit,
        changesAtOnce: Int,
        changesWhenSettled: Int,
    ) {
        model.edit(Side.LEFT, "yaml:[1]")
        model.settled()
        changes = 0

        step(this)
        assertEquals(changesAtOnce, changes)
        model.settled()
        assertEquals(changesAtOnce + changesWhenSettled, changes)
    }

    @Test
    fun `the surest format wins detection`() {
        val a = FakeFormat("a")
        val b = FakeFormat("b")
        val model = model(listOf(FakeFamily("Scored", listOf(a, b)) { listOf(FormatMatch(a, 40), FormatMatch(b, 60)) }))

        model.edit(Side.LEFT, "x")
        model.settled()

        assertEquals(b, model.format(Side.LEFT))
    }

    @Test
    fun `a tie goes to family and format order`() {
        val model = tiedModel()

        model.edit(Side.LEFT, "tie")
        model.settled()

        assertEquals(json, model.format(Side.LEFT))
    }

    @Test
    fun `a tie goes to the side's previous format first`() {
        val model = tiedModel()
        model.edit(Side.LEFT, "yaml:a")
        model.settled()
        model.choose(Side.RIGHT, Pick.Pinned(toml))
        model.choose(Side.RIGHT, Pick.Pinned(json))
        model.settled()

        model.edit(Side.LEFT, "tie")
        model.settled()

        assertEquals(yaml, model.format(Side.LEFT))
    }

    @Test
    fun `a tie goes to the last pinned format second`() {
        val model = tiedModel()
        model.choose(Side.RIGHT, Pick.Pinned(yaml))

        model.edit(Side.LEFT, "tie")
        model.settled()

        assertEquals(yaml, model.format(Side.LEFT))
        // The user's pin on the target stays, though the source's format just changed to it.
        assertEquals(Pick.Pinned(yaml), model.pick(Side.RIGHT))
    }

    /** "tie" is read by JSON and YAML alike. */
    private fun tiedModel(): ConvertingModel {
        val tied =
            FakeFamily("Data", data.formats) { text ->
                if (text == "tie") listOf(FormatMatch(json, 50), FormatMatch(yaml, 50)) else null
            }
        return model(listOf(tied))
    }

    @Test
    fun `a pinned format that fails hints at the detected one`() {
        model.choose(Side.LEFT, Pick.Pinned(json))
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        assertEquals(json, model.format(Side.LEFT))
        assertEquals(Status.Failure("not JSON", null), model.status(Side.LEFT))
        assertEquals("Looks like YAML", model.hint?.message)

        model.applyHint()
        model.settled()

        assertEquals(Pick.Pinned(yaml), model.pick(Side.LEFT))
        assertNull(model.hint)
        assertEquals("json:a", model.text(Side.RIGHT))
    }

    @Test
    fun `detecting the target's format switches the target to the default partner`() {
        model.edit(Side.LEFT, "json:a")
        model.settled()

        assertEquals(Pick.Detect, model.pick(Side.RIGHT))
        assertEquals(yaml, model.format(Side.RIGHT))
        assertEquals("yaml:a", model.text(Side.RIGHT))
    }

    @Test
    fun `a source of another family moves the target to that family`() {
        model.edit(Side.LEFT, "curl:x")
        model.settled()

        assertEquals(requests.format("http"), model.format(Side.RIGHT))
        assertEquals("http:x", model.text(Side.RIGHT))
    }

    @Test
    fun `a target pinned to another family's format detects again`() {
        model.choose(Side.RIGHT, Pick.Pinned(toml))
        model.edit(Side.LEFT, "curl:x")
        model.settled()

        assertEquals(Pick.Detect, model.pick(Side.RIGHT))
        assertEquals(requests.format("http"), model.format(Side.RIGHT))
    }

    @Test
    fun `the target switches to the last other format used as target`() {
        model.choose(Side.RIGHT, Pick.Pinned(toml))
        model.settled()
        model.choose(Side.RIGHT, Pick.Pinned(yaml))
        model.settled()
        model.choose(Side.RIGHT, Pick.Detect)
        model.settled()

        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        assertEquals(toml, model.format(Side.RIGHT))
    }

    @Test
    fun `the target's format chosen as the source's stays, so the tool formats`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.choose(Side.RIGHT, Pick.Pinned(yaml))
        model.settled()
        model.edit(Side.LEFT, "yaml:b")
        model.settled()

        assertEquals(Side.LEFT, model.source)
        assertEquals(yaml, model.format(Side.RIGHT))
        assertEquals("yaml:b", model.text(Side.RIGHT))
    }

    @Test
    fun `pinning the source to the target's format switches the target`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.choose(Side.LEFT, Pick.Pinned(json))
        model.settled()

        assertEquals(yaml, model.format(Side.RIGHT))
    }

    @Test
    fun `visible options are the source's read options and the target's write options, each once`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        assertEquals(listOf(trim, upper, indent), model.visibleOptions())
    }

    @Test
    fun `an option converts again at once`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.setOption("upper", true)

        assertEquals(listOf(Duration.ZERO), scheduler.waiting.map { it.delay })
        model.settled()
        assertEquals("JSON:A", model.text(Side.RIGHT))
    }

    @Test
    fun `options are kept across formats`() {
        model.setOption("upper", true)
        model.edit(Side.LEFT, "curl:a")
        model.settled()

        assertEquals("HTTP:A", model.text(Side.RIGHT))
        assertEquals(true, model.options.flag(upper))
    }

    @Test
    fun `a read error shows its position and keeps the target's text`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.edit(Side.LEFT, "yaml:a\n  !")
        model.settled()

        assertEquals("Line 2, column 3: unexpected !", model.status(Side.LEFT).text)
        assertEquals(Status.Failure("unexpected !", TextPosition(9, 2, 3)), model.status(Side.LEFT))
        assertEquals("json:a", model.text(Side.RIGHT))
    }

    @Test
    fun `a write error shows on the target and keeps its text`() {
        model.edit(Side.LEFT, "yaml:a")
        model.choose(Side.RIGHT, Pick.Pinned(toml))
        model.settled()

        model.edit(Side.LEFT, "yaml:[1]")
        model.settled()

        assertEquals(Status.Failure("TOML cannot hold a top-level array", null), model.status(Side.RIGHT))
        assertEquals("toml:a", model.text(Side.RIGHT))
        assertEquals(Status.Info("YAML · detected", 8, emptyList()), model.status(Side.LEFT))
        assertEquals("TOML cannot hold a top-level array", model.targetChoices.single { it.format == toml }.unavailable)
    }

    @Test
    fun `a family that throws while converting shows it as a read error`() {
        model.choose(Side.LEFT, Pick.Pinned(yaml))
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.edit(Side.LEFT, "yaml:overflow")
        model.settled()

        assertEquals(Status.Failure("StackOverflowError", null), model.status(Side.LEFT))
        assertEquals("json:a", model.text(Side.RIGHT))
    }

    @Test
    fun `a family that throws while checking a target marks it unavailable`() {
        model.edit(Side.LEFT, "yaml:deep")
        model.settled()

        model.checkTargets()
        model.settled()

        assertEquals(listOf(null, null, "TOML overflowed"), model.targetChoices.map { it.unavailable })
    }

    @Test
    fun `a text no format detects keeps the target's text`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.edit(Side.LEFT, "something")
        model.settled()

        assertEquals(Status.Failure("No format detected", null), model.status(Side.LEFT))
        assertNull(model.format(Side.LEFT))
        assertEquals("json:a", model.text(Side.RIGHT))
    }

    @Test
    fun `a blank source empties the target without detection or reading`() {
        model.edit(Side.LEFT, "yaml:a#")
        model.settled()

        model.edit(Side.LEFT, " \n")
        model.settled()

        assertEquals(1, data.reads)
        assertEquals("", model.text(Side.RIGHT))
        assertNull(model.format(Side.LEFT))
        assertEquals(Status.Info(null, 2, emptyList()), model.status(Side.LEFT))
        assertEquals(Status.Info("JSON", 0, emptyList()), model.status(Side.RIGHT))
    }

    @Test
    fun `choosing on the target writes again at once and keeps the source`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()

        model.choose(Side.RIGHT, Pick.Pinned(toml))

        assertEquals(listOf(Duration.ZERO), scheduler.waiting.map { it.delay })
        model.settled()
        assertEquals(Side.LEFT, model.source)
        assertEquals("toml:a", model.text(Side.RIGHT))
    }

    @Test
    fun `swap exchanges texts, picks and statuses without converting`() {
        model.edit(Side.LEFT, "yaml:a#")
        model.settled()

        model.swap()

        assertEquals(emptyList<ManualScheduler.Task>(), scheduler.waiting)
        assertEquals(Side.RIGHT, model.source)
        assertEquals("yaml:a#", model.text(Side.RIGHT))
        assertEquals(Pick.Detect, model.pick(Side.RIGHT))
        assertEquals(json, model.format(Side.LEFT))
        assertEquals("YAML · detected · 7 characters", model.status(Side.RIGHT).text)
        assertEquals("JSON · 7 characters · 1 comment dropped", model.status(Side.LEFT).text)
    }

    @Test
    fun `a conversion still to come after a swap fills the moved target`() {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()
        model.edit(Side.LEFT, "yaml:b")

        model.swap()
        model.settled()

        assertEquals("yaml:b", model.text(Side.RIGHT))
        assertEquals("json:b", model.text(Side.LEFT))
    }

    @Test
    fun `open puts the text on the left, pinned, and converts at once`() {
        model.edit(Side.RIGHT, "json:x")

        model.open("yaml:a", yaml)

        assertEquals(listOf(Duration.ZERO), scheduler.waiting.map { it.delay })
        model.settled()
        assertEquals(Side.LEFT, model.source)
        assertEquals(Pick.Pinned(yaml), model.pick(Side.LEFT))
        assertEquals("json:a", model.text(Side.RIGHT))
    }

    @Test
    fun `open without a format detects it`() {
        model.open("yaml:a", null)
        model.settled()

        assertEquals(Pick.Detect, model.pick(Side.LEFT))
        assertEquals(yaml, model.format(Side.LEFT))
    }

    @Test
    fun `openFile pins the format of the extension and names the files to save after it`(
        @TempDir dir: Path,
    ) {
        val file = Files.writeString(dir.resolve("application.yaml"), "a")

        assertNull(model.openFile(Side.RIGHT, file))
        model.settled()

        assertEquals(Side.RIGHT, model.source)
        assertEquals(Pick.Pinned(yaml), model.pick(Side.RIGHT))
        assertEquals("json:a", model.text(Side.LEFT))
        assertEquals("application.yaml", model.fileName(Side.RIGHT))
        assertEquals("application.json", model.fileName(Side.LEFT))
    }

    @Test
    fun `openFile detects the format of an unknown extension`(
        @TempDir dir: Path,
    ) {
        val file = Files.writeString(dir.resolve("notes.txt"), "yaml:a")

        assertNull(model.openFile(Side.LEFT, file))
        model.settled()

        assertEquals(Pick.Detect, model.pick(Side.LEFT))
        assertEquals(yaml, model.format(Side.LEFT))
    }

    @Test
    fun `openFile refuses a file over 1 MiB and keeps the state`(
        @TempDir dir: Path,
    ) {
        val file = Files.writeString(dir.resolve("big.yaml"), "a".repeat(ConvertingModel.MAX_FILE_SIZE + 1))

        assertEquals("big.yaml is larger than 1 MiB", model.openFile(Side.LEFT, file))
        assertEquals("", model.text(Side.LEFT))
        assertEquals(Pick.Detect, model.pick(Side.LEFT))
    }

    @Test
    fun `openFile reports a missing file`(
        @TempDir dir: Path,
    ) {
        assertEquals("Cannot read gone.yaml: file not found", model.openFile(Side.LEFT, dir.resolve("gone.yaml")))
    }

    @Test
    fun `openFile reports a file that is not UTF-8`(
        @TempDir dir: Path,
    ) {
        val file = Files.write(dir.resolve("latin.yaml"), byteArrayOf(0x61, 0xE4.toByte(), 0x62))

        assertEquals("Cannot read latin.yaml: not UTF-8 text", model.openFile(Side.LEFT, file))
    }

    @Test
    fun `a format without extensions is saved as txt`() {
        val model = model(listOf(FakeFamily("Plain", listOf(FakeFormat("plain", emptyList())))))

        assertEquals("converted.txt", model.fileName(Side.RIGHT))
    }

    @Test
    fun `target choices are the formats of the source's family, all without a source`() {
        assertEquals(listOf("json", "yaml", "toml", "curl", "http"), model.targetChoices.map { it.format.id })

        model.edit(Side.LEFT, "curl:a")
        model.settled()

        assertEquals(listOf("curl", "http"), model.targetChoices.map { it.format.id })
    }

    @Test
    fun `checkTargets marks the formats that cannot hold the source`() {
        model.edit(Side.LEFT, "yaml:[1]")
        model.settled()

        model.checkTargets()
        model.settled()

        assertEquals(
            listOf(
                TargetChoice(json, null),
                TargetChoice(yaml, null),
                TargetChoice(toml, "TOML cannot hold a top-level array"),
            ),
            model.targetChoices,
        )
    }

    @Test
    fun `a check finishing after the source was read again is dropped`() {
        model.edit(Side.LEFT, "yaml:[1]")
        model.settled()
        model.checkTargets()
        scheduler.run(scheduler.waiting.single())

        model.setOption("trim", true)
        scheduler.run(scheduler.waiting.single())
        dispatched.removeLast().run()
        dispatched.removeLast().run()

        assertEquals(listOf(null, null, null), model.targetChoices.map { it.unavailable })
    }

    @Test
    fun `results after dispose are dropped`() {
        model.edit(Side.LEFT, "yaml:a")
        scheduler.run(scheduler.waiting.single())

        model.dispose()
        model.settled()

        assertEquals("", model.text(Side.RIGHT))
    }

    @Test
    fun `a check finishing after dispose is dropped`() {
        model.edit(Side.LEFT, "yaml:[1]")
        model.settled()
        model.checkTargets()
        scheduler.run(scheduler.waiting.single())

        model.dispose()
        model.settled()

        assertEquals(listOf(null, null, null), model.targetChoices.map { it.unavailable })
    }

    @Test
    fun `after dispose changes are ignored and schedule nothing`(
        @TempDir dir: Path,
    ) {
        model.edit(Side.LEFT, "yaml:a")
        model.settled()
        model.dispose()
        changes = 0

        model.edit(Side.LEFT, "yaml:b")
        model.choose(Side.LEFT, Pick.Pinned(json))
        model.setOption("upper", true)
        model.swap()
        model.open("yaml:c", yaml)
        model.openFile(Side.LEFT, Files.writeString(dir.resolve("a.yaml"), "d"))
        model.checkTargets()

        assertEquals(0, changes)
        assertEquals("yaml:a", model.text(Side.LEFT))
    }

    @Test
    fun `converts JSON to YAML with the data family`() {
        val model = ConvertingModel(listOf(DataFamily()), dispatched::addLast, DELAY, scheduler)

        model.edit(Side.LEFT, """{"a": 1}""")
        model.settled()

        assertEquals("JSON · detected", (model.status(Side.LEFT) as Status.Info).format)
        assertEquals("a: 1", model.text(Side.RIGHT).trim())
    }

    companion object {
        private val DELAY = 42.milliseconds

        /** A step on a model holding `yaml:[1]`, with the onChange calls it makes at once and when settled. */
        @JvmStatic
        fun steps(): List<Arguments> =
            listOf(
                step("edit", 1, 1) { it.model.edit(Side.LEFT, "yaml:b") },
                step("choose", 1, 1) { it.model.choose(Side.RIGHT, Pick.Pinned(it.toml)) },
                step("swap", 1, 0) { it.model.swap() },
                step("setOption", 1, 1) { it.model.setOption("upper", true) },
                step("open", 1, 1) { it.model.open("yaml:c", it.yaml) },
                step("checkTargets", 0, 1) { it.model.checkTargets() },
            )

        private fun step(
            name: String,
            changesAtOnce: Int,
            changesWhenSettled: Int,
            step: (ConvertingModelTest) -> Unit,
        ) = Arguments.of(name, step, changesAtOnce, changesWhenSettled)
    }
}
