package dev.sluide.pitestkotlin

import org.objectweb.asm.tree.MethodInsnNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.bytecode.analysis.MethodTree
import org.pitest.mutationtest.build.InterceptorType
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.engine.Mutater
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Drops a mutant only when its mutated instruction is compiler plumbing, never a whole line: a line that also holds a
 * String `==` (`Intrinsics.areEqual`) keeps its real mutants. A kept mutant of inlined code of ours is moved to the
 * file and line it was written on ([CodeLocations]).
 */
class KotlinJunkFilter : MutationInterceptor {
    /** The class of the mutants; pitest calls [begin] with it before every [intercept] (`MutationSource`). */
    private lateinit var current: FilteredClass

    override fun type() = InterceptorType.FILTER

    override fun begin(clazz: ClassTree) {
        current = FilteredClass(clazz)
    }

    override fun intercept(
        mutations: Collection<MutationDetails>,
        m: Mutater,
    ): Collection<MutationDetails> = mutations.filter { current.junkRule(it) == null }.map(current::placed)

    /** Keeps the class until [begin] replaces it: one class's tree, not worth a reset. */
    override fun end() = Unit

    /** The rule that makes [mutant] junk, or null when it is kept. */
    internal fun junkRule(mutant: MutationDetails): JunkRule? = current.junkRule(mutant)
}

/** The class [begin][KotlinJunkFilter.begin] gave, with what the rules read from it, once per class or method. */
private class FilteredClass(
    private val tree: ClassTree,
) {
    private val locations = CodeLocations(tree.rawNode())
    private val plainGetters = HashMap<MethodTree, Boolean>()
    private val inlineDeclarations = HashMap<MethodTree, Boolean>()

    /**
     * pitest found the mutants in the bytes it gives [KotlinJunkFilter.begin] (`MutationSource.createMutations`), so
     * their method and instruction exist; a mutant without them is kept rather than failing the run.
     */
    fun junkRule(mutant: MutationDetails): JunkRule? {
        val method = tree.method(mutant.id.location).orElse(null) ?: return null
        val instruction = method.instructionForIndex(mutant.instructionIndex).orElse(null) ?: return null
        val call = instruction as? MethodInsnNode
        return when {
            call != null && call.owner == INTRINSICS && call.name !in COMPARISONS -> JunkRule.INTRINSICS
            call != null && call.owner in CLOSE_FINALLY_OWNERS -> JunkRule.CLOSE_FINALLY
            call != null && isSpreadPlumbing(call) -> JunkRule.SPREAD
            call != null && call.owner == "kotlin/Result" && call.name in RESULT_BOXING -> JunkRule.RESULT
            locations.of(mutant).isLibraryCode -> JunkRule.INLINED
            inlineDeclarations.getOrPut(method) { isInlineDeclaration(method) } -> JunkRule.INLINE_DECLARATION
            plainGetters.getOrPut(method) { isPlainGetter(method) } -> JunkRule.ACCESSOR
            else -> null
        }
    }

    /**
     * [mutant] at the file and line its code was written on. The same mutant to pitest (same id, so the same mutation
     * and the same tests), reported where it can be read: for the copy of an inline function, at the function's lines.
     */
    fun placed(mutant: MutationDetails): MutationDetails {
        val location = locations.of(mutant)
        return MutationDetails(mutant.id, location.fileName, mutant.description, location.line, mutant.blocks)
    }
}

internal const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"

/**
 * The owners of `closeFinally`, which `use {}` on a `Closeable` and on an `AutoCloseable` calls; it is the only method
 * of theirs a caller can call (`use` is inline only).
 */
private val CLOSE_FINALLY_OWNERS = setOf("kotlin/io/CloseableKt", "kotlin/jdk7/AutoCloseableKt")

/**
 * The boxing of the inline class `Result`: `constructor-impl` returns its argument, `box-impl` and `unbox-impl` wrap and
 * unwrap it. Not `createFailure` and `isFailure-impl`: `Result.failure` and `.isFailure` in our code call them too.
 */
private val RESULT_BOXING = setOf("constructor-impl", "box-impl", "unbox-impl")

/** The Intrinsics that are real code: `==` on objects (`areEqual`) and `compareTo` on numbers (`compare`). */
private val COMPARISONS = setOf("areEqual", "compare")

/** Why a mutant is junk. */
internal enum class JunkRule {
    /** A null check or other `kotlin.jvm.internal.Intrinsics` call; not the comparisons `areEqual` and `compare`. */
    INTRINSICS,

    /** The `closeFinally` that `use {}` calls. */
    CLOSE_FINALLY,

    /**
     * A library's code inlined here, by the file the class's SMAP maps its line to: the stdlib's, kotlinx's, any file
     * outside our packages that is not the class's own.
     */
    INLINED,

    /** The declaration of an inline function of ours, which callers never run; its inlined copies are kept. */
    INLINE_DECLARATION,

    /** A property getter that only loads its field. */
    ACCESSOR,

    /** The copy of an array that a spread operator `*array` makes, or the size and array of its `SpreadBuilder`. */
    SPREAD,

    /** The boxing of a `kotlin.Result`, also in `Result.success(value)`. */
    RESULT,
}
