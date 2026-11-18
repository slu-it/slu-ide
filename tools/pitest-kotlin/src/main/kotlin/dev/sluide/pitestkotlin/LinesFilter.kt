package dev.sluide.pitestkotlin

import org.pitest.bytecode.analysis.ClassTree
import org.pitest.mutationtest.build.InterceptorType
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.engine.Mutater
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Keeps the mutants whose code [allowed] lists, by the file and line it was written on ([CodeLocations]): the copy of
 * an inline function by the function's lines, whether or not KOTLIN_JUNK placed it there before.
 */
class LinesFilter(
    private val allowed: AllowedLines,
) : MutationInterceptor {
    private lateinit var locations: CodeLocations

    override fun type() = InterceptorType.FILTER

    override fun begin(clazz: ClassTree) {
        locations = CodeLocations(clazz.rawNode())
    }

    override fun intercept(
        mutations: Collection<MutationDetails>,
        m: Mutater,
    ): Collection<MutationDetails> = mutations.filter { allowed.allows(locations.of(it)) }

    override fun end() = Unit
}
