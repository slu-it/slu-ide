package dev.sluide.pitestkotlin

import org.pitest.mutationtest.build.InterceptorParameters
import org.pitest.mutationtest.build.MutationInterceptor
import org.pitest.mutationtest.build.MutationInterceptorFactory
import org.pitest.plugin.Feature
import org.pitest.plugin.FeatureParameter

/** The feature `LINES`, off by default: `+LINES(file[<path>])` keeps only the mutants on the file's `path:line` entries. */
class LinesFilterFactory : MutationInterceptorFactory {
    override fun description() = "Allow-list of lines (Thoreau)"

    /** Reads the allow-list once; fails, and with it the pitest run, when the parameter or the list is missing. */
    override fun createInterceptor(params: InterceptorParameters): MutationInterceptor =
        LinesFilter(AllowedLines.fromFile(params.getString(FILE).orElse(null)))

    override fun provides(): Feature =
        Feature
            .named("LINES")
            .withOnByDefault(false)
            .withDescription("Keeps only mutants on the lines of an allow-list file, one `path:line` per line")
            .withParameter(FILE.withDescription("path of the allow-list file"))

    companion object {
        val FILE: FeatureParameter = FeatureParameter.named("file")
    }
}
