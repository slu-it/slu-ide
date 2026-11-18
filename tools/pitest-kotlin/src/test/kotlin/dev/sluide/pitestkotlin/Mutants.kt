package dev.sluide.pitestkotlin

import org.objectweb.asm.tree.ClassNode
import org.pitest.bytecode.analysis.ClassTree
import org.pitest.classinfo.ClassByteArraySource
import org.pitest.classinfo.ClassName
import org.pitest.classpath.ClassloaderByteArraySource
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.engine.MutationDetails
import org.pitest.mutationtest.engine.gregor.GregorMutater
import org.pitest.mutationtest.engine.gregor.config.Mutator

/** The mutator set the mutation runs use (frozen in the root build). */
val MUTATORS =
    listOf("DEFAULTS", "REMOVE_CONDITIONALS_EQUAL_IF", "REMOVE_CONDITIONALS_EQUAL_ELSE", "NON_VOID_METHOD_CALLS")

/** Runs pitest's mutater over classes of [source], as pitest does before it hands the mutants to the interceptors. */
class Mutants(
    private val source: ClassByteArraySource = ClassloaderByteArraySource.fromContext(),
) {
    private val mutater = GregorMutater(source, { true }, Mutator.fromStrings(MUTATORS))

    fun of(className: String): List<MutationDetails> = mutater.findMutations(ClassName.fromString(className))

    /** The mutants [filter] keeps, after [prepare] changed the class it is given (not the class that is mutated). */
    fun filtered(
        className: String,
        filter: MutationInterceptor,
        prepare: (ClassNode) -> Unit = {},
    ): List<MutationDetails> {
        val tree = ClassTree.fromBytes(source.getBytes(className).orElseThrow())
        prepare(tree.rawNode())
        filter.begin(tree)
        val kept = filter.intercept(of(className), mutater).toList()
        filter.end()
        return kept
    }

    /** The mutants [filters] keep, applied one after the other, each given the class as pitest gives it. */
    fun filtered(
        className: String,
        filters: List<MutationInterceptor>,
    ): List<MutationDetails> =
        filters.fold(of(className)) { mutants, filter ->
            filter.begin(ClassTree.fromBytes(source.getBytes(className).orElseThrow()))
            filter.intercept(mutants, mutater).toList().also { filter.end() }
        }
}

/**
 * `line Mutator: description` without the method a return mutant names, e.g.
 * `7 VoidMethodCall: removed call to kotlin/jvm/internal/Intrinsics::checkNotNull`.
 */
val MutationDetails.label: String
    get() = "$lineNumber $mutatorAndDescription"

/** `file:line Mutator: description`, with the file name pitest reports (a path for code inlined from another file). */
val MutationDetails.placedLabel: String
    get() = "$filename:$lineNumber $mutatorAndDescription"

private val MutationDetails.mutatorAndDescription: String
    get() = "${mutator.substringAfterLast('.').replace("Mutator", "")}: ${description.substringBefore(" for ")}"
