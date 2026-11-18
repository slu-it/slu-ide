package dev.sluide.pitestkotlin

import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.build.MutationInterceptorFactory
import org.pitest.plugin.Feature

/** The feature `KOTLIN_JUNK`, on by default: drops mutants of bytecode the Kotlin compiler adds ([KotlinJunkFilter]). */
class KotlinJunkFilterFactory : MutationInterceptorFactory {
    override fun description() = "Kotlin junk filter (Thoreau)"

    override fun createInterceptor(params: InterceptorParameters): MutationInterceptor = KotlinJunkFilter()

    override fun provides(): Feature =
        Feature
            .named("KOTLIN_JUNK")
            .withOnByDefault(true)
            .withDescription(
                "Drops mutants of Kotlin compiler plumbing (null checks, inlined library code, declarations of " +
                    "inline functions, plain property getters, spread copies, Result boxing), judged by the mutated " +
                    "instruction; places the inlined code of ours at the file and line it was written on",
            )
}
