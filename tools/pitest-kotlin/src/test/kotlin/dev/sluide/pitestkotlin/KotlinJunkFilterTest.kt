package dev.sluide.pitestkotlin

import dev.sluide.pitestkotlin.fixtures.DefaultArguments
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs pitest's mutater with the frozen mutator set over the fixtures in `fixtures/` and checks which mutants the filter
 * keeps and drops, as `line Mutator: description` ([label]). Line numbers are those of the fixture files.
 */
class KotlinJunkFilterTest {
    private val mutants = Mutants()

    @Test
    fun `drops the null checks of parameters, of !! and of platform values, and keeps the rest of their lines`() {
        val (kept, dropped) = filter("NullChecks")

        assertEquals(
            listOf(
                "7 NonVoidMethodCall: removed call to java/lang/String::length",
                "7 NonVoidMethodCall: removed call to java/lang/String::length",
                "7 Math: Replaced integer addition with subtraction",
                "7 PrimitiveReturns: replaced int return with 0",
                "10 NonVoidMethodCall: removed call to java/lang/System::getProperty",
                "11 NonVoidMethodCall: removed call to kotlin/text/StringsKt::trim",
                "11 NonVoidMethodCall: removed call to java/lang/Object::toString",
                "11 EmptyObjectReturnVals: replaced return value with \"\"",
            ),
            kept,
        )
        assertEquals(
            listOf(
                "0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter",
                "7 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNull",
                "10 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue",
            ),
            dropped,
        )
    }

    @Test
    fun `keeps the mutants of a String or object == and of the jumps after it, and of a compareTo`() {
        val (kept, dropped) = filter("StringEquals")

        assertEquals(
            listOf(
                "5 NonVoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::areEqual",
                "5 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "5 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "5 BooleanTrueReturnVals: replaced boolean return with true",
                "5 BooleanFalseReturnVals: replaced boolean return with false",
                "6 BooleanTrueReturnVals: replaced boolean return with true",
                "6 BooleanFalseReturnVals: replaced boolean return with false",
                "12 NonVoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::areEqual",
                "12 BooleanTrueReturnVals: replaced boolean return with true",
                "12 BooleanFalseReturnVals: replaced boolean return with false",
                "17 NonVoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::compare",
                "17 PrimitiveReturns: replaced int return with 0",
            ),
            kept,
        )
        assertEquals(
            List(3) {
                "0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter"
            },
            dropped,
        )
    }

    @Test
    fun `drops the closeFinally calls of use on a Closeable and an AutoCloseable and keeps the code in its block`() {
        val (kept, dropped) = filter("UseBlock")

        assertEquals(
            listOf(
                "6 NonVoidMethodCall: removed call to java/io/InputStream::read",
                "6 PrimitiveReturns: replaced int return with 0",
                "8 NonVoidMethodCall: removed call to java/lang/Object::hashCode",
                "8 PrimitiveReturns: replaced int return with 0",
            ),
            kept,
        )
        assertEquals(
            listOf(
                "0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter",
                "6 VoidMethodCall: removed call to kotlin/io/CloseableKt::closeFinally",
                "6 VoidMethodCall: removed call to kotlin/io/CloseableKt::closeFinally",
                "0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter",
                "8 VoidMethodCall: removed call to kotlin/jdk7/AutoCloseableKt::closeFinally",
                "8 VoidMethodCall: removed call to kotlin/jdk7/AutoCloseableKt::closeFinally",
            ),
            dropped,
        )
    }

    @Test
    fun `drops the inlined bodies of filter and map and keeps the lambdas and references given to them`() {
        val (kept, dropped) = filter("InlineCollections")

        assertEquals(INLINE_COLLECTIONS_KEPT, kept)
        assertEquals(setOf(0, 18, 20, 21, 22, 24, 25, 26, 29), dropped.map(::lineOf).toSet())
    }

    @Test
    fun `keeps the mutants of a lambda that is not inlined`() {
        val (kept, dropped) = filter("NotInlinedLambda")

        assertEquals(
            listOf(
                "4 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "4 ConditionalsBoundary: changed conditional boundary",
                "4 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "4 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "4 BooleanTrueReturnVals: replaced boolean return with true",
                "4 BooleanFalseReturnVals: replaced boolean return with false",
            ),
            kept,
        )
        assertEquals(emptyList(), dropped)
    }

    @Test
    fun `drops getters that only load their field, also of a companion and an object, not those with logic`() {
        val (kept, dropped) = filter("Properties")
        val (companionKept, companionDropped) = filter("Properties\$Companion")
        val (objectKept, objectDropped) = filter("PropertyDefaults")

        assertEquals(
            listOf(
                "9 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
                "9 ConditionalsBoundary: changed conditional boundary",
                "9 Math: Replaced integer multiplication with division",
                "9 PrimitiveReturns: replaced int return with 0",
                // A field of another instance, a call on this and a static call are no getters of this class.
                "15 PrimitiveReturns: replaced int return with 0",
                "17 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Properties::hashCode",
                "17 PrimitiveReturns: replaced int return with 0",
                "19 NonVoidMethodCall: removed call to java/lang/System::nanoTime",
                "19 PrimitiveReturns: replaced long return with 0",
                "22 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
            ),
            kept,
        )
        assertEquals(
            listOf(
                "0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter",
                "4 EmptyObjectReturnVals: replaced return value with \"\"",
                "6 PrimitiveReturns: replaced int return with 0",
            ),
            dropped,
        )
        // A call of the accessor that is thrown, not returned, is no getter.
        assertEquals(
            listOf(
                "25 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Properties::access\$getFailure\$cp",
            ),
            companionKept,
        )
        assertEquals(
            listOf(
                "22 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Properties::access\$getDefaults\$cp",
                "22 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
            ),
            companionDropped,
        )
        assertEquals(
            listOf("30 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf"),
            objectKept,
        )
        assertEquals(
            listOf("30 EmptyObjectReturnVals: replaced return value with Collections.emptyList"),
            objectDropped,
        )
    }

    @Test
    fun `drops the array copy and the builder plumbing of a spread, and keeps copies we make and the elements`() {
        val (kept, dropped) = filter("Spread")

        assertEquals(
            listOf(
                "4 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "4 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                "6 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "8 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "10 VoidMethodCall: removed call to kotlin/jvm/internal/SpreadBuilder::addSpread",
                "10 VoidMethodCall: removed call to kotlin/jvm/internal/SpreadBuilder::add",
                "10 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "10 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                "12 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Spread::names",
                "12 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "12 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                "14 NullReturnVals: replaced return value with null",
                "17 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Spread::names",
                "17 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "17 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Spread::names",
                "17 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "17 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "17 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                // Our copy of items at the length of other, then the spread's copy of it.
                "22 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "22 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "22 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                // Our copy at a length from a call, and a fill to the length, are no spread either.
                "24 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/SpreadKt::access\$half",
                "24 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "24 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::listOf",
                "24 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
                "26 VoidMethodCall: removed call to java/util/Arrays::fill",
            ),
            kept,
        )
        assertEquals(
            listOf(
                "4 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "10 NonVoidMethodCall: removed call to kotlin/jvm/internal/SpreadBuilder::size",
                "10 NonVoidMethodCall: removed call to kotlin/jvm/internal/SpreadBuilder::toArray",
                "12 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "22 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
                "24 NonVoidMethodCall: removed call to java/util/Arrays::copyOf",
            ),
            dropped.filterNot { "Intrinsics" in it },
        )
    }

    @Test
    fun `drops the boxing of a Result and keeps its failures and checks`() {
        val (kept, dropped) = filter("ResultCalls")

        assertEquals(
            listOf(
                "4 NonVoidMethodCall: removed call to java/lang/Integer::parseInt",
                "4 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "4 NonVoidMethodCall: removed call to kotlin/ResultKt::createFailure",
                "4 NonVoidMethodCall: removed call to kotlin/Result::isFailure-impl",
                "4 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "4 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "4 EmptyObjectReturnVals: replaced Integer return value with 0",
                "6 NonVoidMethodCall: removed call to java/lang/Integer::parseInt",
                "6 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
                "6 NonVoidMethodCall: removed call to kotlin/ResultKt::createFailure",
                "8 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/ResultCalls::attempt-IoAF18A",
                "8 NonVoidMethodCall: removed call to kotlin/Result::isSuccess-impl",
                "8 BooleanTrueReturnVals: replaced boolean return with true",
                "8 BooleanFalseReturnVals: replaced boolean return with false",
                "10 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/ResultCalls::attempt-IoAF18A",
                "12 NonVoidMethodCall: removed call to kotlin/collections/CollectionsKt::first",
                "12 NonVoidMethodCall: removed call to kotlin/Result::isFailure-impl",
                "12 BooleanTrueReturnVals: replaced boolean return with true",
                "12 BooleanFalseReturnVals: replaced boolean return with false",
                // The constructor of our own value class runs its init block.
                "14 NonVoidMethodCall: removed call to java/lang/String::length",
                "14 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/Count::constructor-impl",
                "14 PrimitiveReturns: replaced int return with 0",
            ),
            kept,
        )
        assertEquals(
            listOf(
                "4 NonVoidMethodCall: removed call to kotlin/Result::constructor-impl",
                "4 NonVoidMethodCall: removed call to kotlin/Result::constructor-impl",
                "6 NonVoidMethodCall: removed call to kotlin/Result::constructor-impl",
                "6 NonVoidMethodCall: removed call to kotlin/Result::constructor-impl",
                "10 NonVoidMethodCall: removed call to kotlin/Result::box-impl",
                "12 NonVoidMethodCall: removed call to kotlin/Result::unbox-impl",
            ),
            dropped.filterNot { "Intrinsics" in it },
        )
    }

    @Test
    fun `keeps the mutants of elvis returns and of the line whose result Kotlin checks for null`() {
        val (kept, dropped) = filter("ElvisReturns")

        assertEquals(
            listOf(
                "5 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "5 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "5 PrimitiveReturns: replaced int return with 0",
                "6 NonVoidMethodCall: removed call to java/lang/String::length",
                "6 PrimitiveReturns: replaced int return with 0",
                "10 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "10 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "10 EmptyObjectReturnVals: replaced return value with \"\"",
                "11 NonVoidMethodCall: removed call to java/lang/String::toUpperCase",
                "11 EmptyObjectReturnVals: replaced return value with \"\"",
            ),
            kept,
        )
        assertEquals(
            listOf("11 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue"),
            dropped,
        )
    }

    @Test
    fun `keeps the mutants of a when with subject, on numbers and on Strings`() {
        val (kept, dropped) = filter("WhenSubject")

        assertEquals(
            listOf(
                "9 EmptyObjectReturnVals: replaced return value with \"\"",
                "13 NonVoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::areEqual",
                "13 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "13 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "14 NonVoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::areEqual",
                "14 RemoveConditional_EQUAL_IF: removed conditional - replaced equality check with true",
                "14 RemoveConditional_EQUAL_ELSE: removed conditional - replaced equality check with false",
                "16 PrimitiveReturns: replaced int return with 0",
            ),
            kept,
        )
        assertEquals(
            listOf("0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter"),
            dropped,
        )
    }

    @Test
    fun `a default-argument bridge leaves no mutants to drop`() {
        // pitest's GregorMutater skips synthetic methods (but lambda$…), and greet$default is one.
        assertTrue(DefaultArguments::class.java.declaredMethods.any { it.name == "greet\$default" && it.isSynthetic })
        assertEquals(setOf("greet"), mutants.of("$FIXTURES.DefaultArguments").map { it.method }.toSet())
    }

    @Test
    fun `a string template leaves no mutants of its own to drop`() {
        val (kept, dropped) = filter("StringTemplate")

        // Kotlin compiles templates to invokedynamic makeConcatWithConstants, which pitest does not mutate.
        assertEquals(listOf("7 EmptyObjectReturnVals: replaced return value with \"\""), kept)
        assertEquals(
            listOf("0 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter"),
            dropped,
        )
    }

    private fun lineOf(label: String) = label.substringBefore(' ').toInt()

    /** The labels of the mutants the filter keeps and of those it drops, each in pitest's order. */
    private fun filter(fixture: String): Pair<List<String>, List<String>> {
        val className = "$FIXTURES.$fixture"
        val all = mutants.of(className)
        val kept = mutants.filtered(className, KotlinJunkFilter()).toSet()
        return all.filter { it in kept }.map { it.label } to all.filterNot { it in kept }.map { it.label }
    }
}

private const val FIXTURES = "dev.sluide.pitestkotlin.fixtures"

/**
 * The mutants of `InlineCollections` outside the inlined stdlib bodies, which the SMAP maps to `_Collections.kt`. The
 * boxing of a lambda's result (`Integer.valueOf`) carries our line and stays.
 */
private val INLINE_COLLECTIONS_KEPT =
    listOf(
        "7 NonVoidMethodCall: removed call to java/lang/String::length",
        "7 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
        "7 ConditionalsBoundary: changed conditional boundary",
        "7 NonVoidMethodCall: removed call to kotlin/text/StringsKt::startsWith\$default",
        "8 NonVoidMethodCall: removed call to java/lang/String::length",
        "8 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
        "8 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
        "10 NonVoidMethodCall: removed call to java/lang/String::length",
        "10 NonVoidMethodCall: removed call to java/lang/Integer::valueOf",
        "10 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
        "12 NonVoidMethodCall: removed call to dev/sluide/pitestkotlin/fixtures/InlineCollections::isShort",
        "12 EmptyObjectReturnVals: replaced return value with Collections.emptyList",
        "14 NonVoidMethodCall: removed call to java/lang/String::length",
        "14 RemoveConditional_ORDER_ELSE: removed conditional - replaced comparison check with false",
        "14 ConditionalsBoundary: changed conditional boundary",
        "14 BooleanTrueReturnVals: replaced boolean return with true",
        "14 BooleanFalseReturnVals: replaced boolean return with false",
    )
