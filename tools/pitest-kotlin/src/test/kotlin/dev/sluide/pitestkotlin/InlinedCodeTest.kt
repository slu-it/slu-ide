package dev.sluide.pitestkotlin

import org.pitest.mutationtest.engine.MutationDetails
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * KOTLIN_JUNK on inlined code: mutants are judged and placed by the file and line the class's SMAP maps them to. Our
 * lambdas in stdlib bodies and the copies of our own inline functions are kept at the lines they were written on; the
 * stdlib's code and the declarations of our inline functions, which callers never run, are dropped.
 */
class InlinedCodeTest {
    private val mutants = Mutants()

    @Test
    fun `drops the stdlib code of sortedBy, compareBy and thenBy and keeps our selector lambdas`() {
        val sortedBy = judge("SortedSelectors\$byLength\$\$inlined\$sortedBy\$1")
        val thenBy = judge("SortedSelectors\$byLengthThenName\$\$inlined\$thenBy\$1")
        val compareBy = judge("SortedSelectors\$byLengthThenName\$\$inlined\$compareBy\$1")

        assertEquals(SELECTOR_LAMBDA, sortedBy.found)
        assertEquals(COMPARE_VALUES, sortedBy.dropped)
        assertEquals(SELECTOR_LAMBDA, compareBy.found)
        assertEquals(COMPARE_VALUES, compareBy.dropped)
        assertEquals(
            List(2) {
                "326 NonVoidMethodCall: removed call to java/lang/String::toLowerCase"
            },
            thenBy.found,
        )
        assertEquals(
            listOf(
                "143 NonVoidMethodCall: removed call to java/util/Comparator::compare",
                "144 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "144 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "144 NonVoidMethodCall: removed call to kotlin/comparisons/ComparisonsKt::compareValues",
                "144 PrimitiveReturns: replaced int return with 0",
            ),
            thenBy.dropped.filterNot { "Intrinsics" in it },
        )
    }

    @Test
    fun `places our selector lambdas at the path and line of the file they were written in`() {
        val kept = judge("SortedSelectors\$byLength\$\$inlined\$sortedBy\$1").kept

        assertEquals(
            listOf(
                "$FIXTURE_PATH/SortedSelectors.kt:4 NonVoidMethodCall: removed call to java/lang/String::length",
                "$FIXTURE_PATH/SortedSelectors.kt:4 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "$FIXTURE_PATH/SortedSelectors.kt:4 NonVoidMethodCall: removed call to java/lang/String::length",
                "$FIXTURE_PATH/SortedSelectors.kt:4 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
            ),
            kept.map { it.placedLabel },
        )
    }

    @Test
    fun `keeps the inlined copies of our own inline functions at their lines, by name in their own file`() {
        val kept =
            judge("OwnInline").kept.filter {
                it.method in
                    setOf("small", "large", "startsWithText", "doubledTwice", "scaledCount", "clamped", "clampTo")
            }

        assertEquals(
            listOf(
                // small and large run bounded's loop and guard, at bounded's lines 13 to 15.
                "OwnInline.kt:13 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:13 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:14 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:14 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:4 Math: Replaced integer multiplication with division",
                "OwnInline.kt:15 Math: Replaced integer addition with subtraction",
                "OwnInline.kt:13 Increments: Changed increment from 1 to -1",
                "OwnInline.kt:4 PrimitiveReturns: replaced int return with 0",
                "OwnInline.kt:13 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:13 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:14 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:14 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:6 Math: Replaced integer addition with subtraction",
                "OwnInline.kt:15 Math: Replaced integer addition with subtraction",
                "OwnInline.kt:13 Increments: Changed increment from 1 to -1",
                "OwnInline.kt:6 PrimitiveReturns: replaced int return with 0",
                // firstIs, reified, at its line 22.
                "OwnInline.kt:22 NonVoidMethodCall: removed call to java/util/Collection::isEmpty",
                "OwnInline.kt:22 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "OwnInline.kt:22 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "OwnInline.kt:22 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "OwnInline.kt:22 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "OwnInline.kt:22 NonVoidMethodCall: removed call to java/util/List::get",
                "OwnInline.kt:22 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "OwnInline.kt:22 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "OwnInline.kt:20 BooleanTrueReturnVals: replaced boolean return with true",
                "OwnInline.kt:20 BooleanFalseReturnVals: replaced boolean return with false",
                // twice, internal (name-mangled), at its line 27.
                "OwnInline.kt:27 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:27 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:29 Math: Replaced integer multiplication with division",
                "OwnInline.kt:29 Math: Replaced integer multiplication with division",
                "OwnInline.kt:29 PrimitiveReturns: replaced int return with 0",
                // scaled, with a value-class parameter (name-mangled), at its line 34.
                "OwnInline.kt:36 NonVoidMethodCall: removed call to java/lang/String::length",
                "OwnInline.kt:36 NonVoidMethodCall: removed call to $FIXTURE_PATH/Count::constructor-impl",
                "OwnInline.kt:34 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OwnInline.kt:34 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:36 Math: Replaced integer addition with subtraction",
                "OwnInline.kt:36 PrimitiveReturns: replaced int return with 0",
                // clampTo, declared in another file: placed at its path.
                "$FIXTURE_PATH/OwnInlineLibrary.kt:6 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "$FIXTURE_PATH/OwnInlineLibrary.kt:6 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:38 PrimitiveReturns: replaced int return with 0",
                // A caller named as the inline function it calls is no declaration.
                "$FIXTURE_PATH/OwnInlineLibrary.kt:6 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "$FIXTURE_PATH/OwnInlineLibrary.kt:6 ConditionalsBoundary: changed conditional boundary",
                "OwnInline.kt:40 PrimitiveReturns: replaced int return with 0",
            ),
            kept.map { it.placedLabel },
        )
    }

    @Test
    fun `drops the declarations of our inline functions, private, internal, value-class and top-level ones`() {
        val ownInline = judge("OwnInline")
        val library = judge("OwnInlineLibraryKt")

        // A reified one is synthetic, which pitest does not mutate.
        assertEquals(emptyList(), mutants.of("$FIXTURES.OwnInline").filter { it.method == "firstIs" })
        assertEquals(
            listOf(
                "13 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "13 ConditionalsBoundary: changed conditional boundary",
                "14 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "14 ConditionalsBoundary: changed conditional boundary",
                "15 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "15 NonVoidMethodCall: removed call to kotlin/jvm/functions/Function1::invoke",
                "15 NonVoidMethodCall: removed call to java/lang/Number::intValue",
                "15 Math: Replaced integer addition with subtraction",
                "13 Increments: Changed increment from 1 to -1",
                "17 PrimitiveReturns: replaced int return with 0",
                "27 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "27 ConditionalsBoundary: changed conditional boundary",
                "27 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "27 NonVoidMethodCall: removed call to kotlin/jvm/functions/Function1::invoke",
                "27 NonVoidMethodCall: removed call to kotlin/jvm/functions/Function1::invoke",
                "27 NonVoidMethodCall: removed call to java/lang/Number::intValue",
                "27 PrimitiveReturns: replaced int return with 0",
                "34 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "34 ConditionalsBoundary: changed conditional boundary",
                "34 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "34 NonVoidMethodCall: removed call to kotlin/jvm/functions/Function1::invoke",
                "34 NonVoidMethodCall: removed call to java/lang/Number::intValue",
                "34 PrimitiveReturns: replaced int return with 0",
            ),
            ownInline.dropped.filterNot { "Intrinsics" in it },
        )
        assertEquals(emptyList(), library.found)
        assertEquals(
            listOf(
                "6 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "6 ConditionalsBoundary: changed conditional boundary",
                "6 PrimitiveReturns: replaced int return with 0",
            ),
            library.dropped,
        )
    }

    @Test
    fun `keeps the code of a class outside our packages that inlines nothing`() {
        val kept = mutants.filtered("outside.OutsidePackage", KotlinJunkFilter())

        assertEquals(
            listOf(
                "OutsidePackage.kt:5 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "OutsidePackage.kt:5 ConditionalsBoundary: changed conditional boundary",
                "OutsidePackage.kt:5 Math: Replaced integer multiplication with division",
                "OutsidePackage.kt:5 PrimitiveReturns: replaced int return with 0",
            ),
            kept.map { it.placedLabel },
        )
    }

    @Test
    fun `keeps the declaration of an inline function in a class without local-variable tables`() {
        val kept =
            mutants.filtered("$FIXTURES.OwnInlineLibraryKt", KotlinJunkFilter()) { node ->
                node.methods.forEach { it.localVariables = null }
            }

        assertEquals(
            listOf(
                "6 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "6 ConditionalsBoundary: changed conditional boundary",
                "6 PrimitiveReturns: replaced int return with 0",
            ),
            kept.map { it.label },
        )
    }

    /** The mutants KOTLIN_JUNK keeps, as it returns them and as pitest found them, and those it drops. */
    private fun judge(fixture: String): Judged {
        val className = "$FIXTURES.$fixture"
        val kept = mutants.filtered(className, KotlinJunkFilter())
        val (found, dropped) = mutants.of(className).partition { it in kept }
        return Judged(kept, found.map { it.label }, dropped.map { it.label })
    }

    /** [kept] as the filter returns them, [found] and [dropped] by their labels as pitest found them. */
    private class Judged(
        val kept: List<MutationDetails>,
        val found: List<String>,
        val dropped: List<String>,
    )
}

private const val FIXTURES = "dev.sluide.pitestkotlin.fixtures"
private const val FIXTURE_PATH = "dev/sluide/pitestkotlin/fixtures"

/** `{ it.length }` of sortedBy and compareBy, run for both values compared. */
private val SELECTOR_LAMBDA =
    listOf(
        "326 NonVoidMethodCall: removed call to java/lang/String::length",
        "326 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
        "326 NonVoidMethodCall: removed call to java/lang/String::length",
        "326 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
    )

/** The stdlib's `compareValues(a, b)` of the comparator, on its line 101 of `Comparisons.kt`. */
private val COMPARE_VALUES =
    listOf(
        "101 NonVoidMethodCall: removed call to kotlin/comparisons/ComparisonsKt::compareValues",
        "101 PrimitiveReturns: replaced int return with 0",
    )
