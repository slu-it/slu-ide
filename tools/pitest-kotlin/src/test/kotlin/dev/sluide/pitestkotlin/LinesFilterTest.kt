package dev.sluide.pitestkotlin

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.tree.ClassNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassName
import org.pitest.classpath.ClassloaderByteArraySource
import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.engine.Location
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.MutationIdentifier
import org.pitest.mutationtest.engine.gregor.GregorMutater
import org.pitest.plugin.FeatureParser
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `+LINES(file[...])` as pitest parses it, over mutants of the classes a Kotlin file compiles to. */
class LinesFilterTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `keeps a mutant on a listed line and drops one on another line of the same file`() {
        val kept =
            filter(
                listOf("modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:166"),
                mutant("dev.sluide.git.GitPush", 166),
                mutant("dev.sluide.git.GitPush", 167),
            )

        assertEquals(listOf("dev.sluide.git.GitPush:166"), kept)
    }

    @Test
    fun `matches the nested, companion, lambda and facade classes of the file by its name`() {
        val classes =
            listOf("GitPush\$Setting", "GitPush\$Companion", "GitPush\$push\$1", "GitPushKt").map {
                "dev.sluide.git.$it"
            }
        val mutants = classes.flatMap { listOf(mutant(it, 166), mutant(it, 167)) }

        val kept = filter(listOf("modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:166"), *mutants.toTypedArray())

        assertEquals(classes.map { "$it:166" }, kept)
    }

    @Test
    fun `matches the file name and the package only at a slash`() {
        val kept =
            filter(
                listOf("modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:166"),
                mutant("Push", 166, file = "Push.kt"),
                mutant("luide.git.GitPush", 166),
                mutant("git.GitPush", 166),
            )

        // A package `git` is a real suffix: the path alone cannot tell it from `dev.sluide.git`.
        assertEquals(listOf("git.GitPush:166"), kept)
    }

    @Test
    fun `matches a file of the default package at the repository root`() {
        val kept = filter(listOf("Push.kt:3"), mutant("Push", 3, file = "Push.kt"), mutant("Push", 4, file = "Push.kt"))

        assertEquals(listOf("Push:3"), kept)
    }

    @Test
    fun `fails when the feature is on without its file parameter`() {
        val failure = assertFailsWith<IllegalArgumentException> { interceptor("+LINES") }

        assertEquals("LINES needs the allow-list file: +LINES(file[<path>])", failure.message)
    }

    @Test
    fun `fails when the allow-list file does not exist`() {
        val missing = folder.resolve("missing.txt")

        val failure = assertFailsWith<IllegalArgumentException> { interceptor("+LINES(file[$missing])") }

        assertEquals("LINES: the allow-list $missing does not exist", failure.message)
    }

    @Test
    fun `fails when the allow-list holds no entry`() {
        val empty = folder.resolve("empty.txt").apply { writeText("\n  \n") }

        val failure = assertFailsWith<IllegalArgumentException> { interceptor("+LINES(file[$empty])") }

        assertEquals("LINES: the allow-list $empty is empty", failure.message)
    }

    @Test
    fun `keeps the inlined copies on a listed line of another file of ours, also as KOTLIN_JUNK placed them`() {
        val entries = listOf("$FIXTURE_SOURCES/OwnInlineLibrary.kt:6")

        assertEquals(
            listOf(
                "58 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "58 ConditionalsBoundary: changed conditional boundary",
                "59 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "59 ConditionalsBoundary: changed conditional boundary",
            ),
            filterFixture(entries).map { it.label },
        )
        assertEquals(
            List(2) {
                listOf(
                    "$FIXTURE_PATH/OwnInlineLibrary.kt:6 RemoveConditional_ORDER_ELSE: removed conditional - " +
                        "replaced comparison check with false",
                    "$FIXTURE_PATH/OwnInlineLibrary.kt:6 ConditionalsBoundary: changed conditional boundary",
                )
            }.flatten(),
            filterFixture(entries, afterJunk = true).map { it.placedLabel },
        )
    }

    @Test
    fun `keeps the inlined copies on a listed line of the class's own file`() {
        val entries = listOf("$FIXTURE_SOURCES/OwnInline.kt:13")

        assertEquals(
            listOf(44, 44, 44, 50, 50, 50, 13, 13, 13),
            filterFixture(entries).map { it.lineNumber },
        )
        assertEquals(
            List(2) {
                listOf(
                    "OwnInline.kt:13 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                    "OwnInline.kt:13 ConditionalsBoundary: changed conditional boundary",
                    "OwnInline.kt:13 Increments: Changed increment from 1 to -1",
                )
            }.flatten(),
            filterFixture(entries, afterJunk = true).map { it.placedLabel },
        )
    }

    @Test
    fun `drops the inlined copies of a file the list does not name on that line`() {
        val kept = filterFixture(listOf("$FIXTURE_SOURCES/OwnInline.kt:6"), afterJunk = true)

        assertEquals(
            listOf(
                "OwnInline.kt:6 Math: Replaced integer addition with subtraction",
                "OwnInline.kt:6 PrimitiveReturns: replaced int return with 0",
            ),
            kept.map { it.placedLabel },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["GitPush.kt:x", "GitPush.kt", "GitPush.kt:", ":3"])
    fun `fails on an entry that is no path and line, naming the file and the entry`(entry: String) {
        val list = folder.resolve("lines.txt").apply { writeText("GitPull.kt:3\n$entry\n") }

        val failure = assertFailsWith<IllegalArgumentException> { interceptor("+LINES(file[$list])") }

        assertEquals("LINES: the allow-list $list has an entry that is no path:line: $entry", failure.message)
    }

    /** LINES over the mutants of `OwnInline`, after KOTLIN_JUNK when [afterJunk]. */
    private fun filterFixture(
        entries: List<String>,
        afterJunk: Boolean = false,
    ): List<MutationDetails> {
        val list = folder.resolve("lines.txt").apply { writeText(entries.joinToString("\n", postfix = "\n")) }
        val lines = interceptor("+LINES(file[$list])")
        val filters = if (afterJunk) listOf(KotlinJunkFilter(), lines) else listOf(lines)
        return Mutants().filtered("dev.sluide.pitestkotlin.fixtures.OwnInline", filters)
    }

    /** LINES over [mutants], each given its class as pitest does: here a class without an SMAP, of the mutant's file. */
    private fun filter(
        entries: List<String>,
        vararg mutants: MutationDetails,
    ): List<String> {
        val list = folder.resolve("lines.txt").apply { writeText(entries.joinToString("\n", postfix = "\n")) }
        val lines = interceptor("+LINES(file[$list])")
        val mutater = GregorMutater(ClassloaderByteArraySource.fromContext(), { true }, emptyList())
        return mutants
            .flatMap { mutant ->
                val clazz =
                    ClassNode().apply {
                        name = mutant.className.asInternalName()
                        sourceFile = mutant.filename
                    }
                lines.begin(ClassTree(clazz))
                lines.intercept(listOf(mutant), mutater).also { lines.end() }
            }.map { "${it.className.asJavaName()}:${it.lineNumber}" }
    }

    private fun interceptor(feature: String) =
        LinesFilterFactory().createInterceptor(
            InterceptorParameters(
                FeatureParser().parseFeatures(listOf(feature)).single(),
                null,
                null,
                null,
                null,
                null,
                null,
            ),
        )

    /** A mutant of [className] at [line]; Kotlin names the source file of every class of `GitPush.kt` so. */
    private fun mutant(
        className: String,
        line: Int,
        file: String = "GitPush.kt",
    ) = MutationDetails(
        MutationIdentifier(Location(ClassName.fromString(className), "m", "()V"), 1, "M"),
        file,
        "a mutant",
        line,
        0,
    )
}

private const val FIXTURE_PATH = "dev/sluide/pitestkotlin/fixtures"
private const val FIXTURE_SOURCES = "tools/pitest-kotlin/src/test/kotlin/$FIXTURE_PATH"
