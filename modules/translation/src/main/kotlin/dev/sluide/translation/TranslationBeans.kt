package dev.sluide.translation

import dev.sluide.localmodels.ModelInstaller
import dev.sluide.translation.detect.LinguaDetector
import dev.sluide.translation.engine.LanguageDetector
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.opusmt.OpusMtEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The app-wide parts of the Translate tool (ADR 0126): the Opus-MT engine, the language detector and the installer of
 * the pair models. Nothing loads or downloads at startup: the engine loads a model on first use, Lingua on the first
 * detection, and the installer reads the disk only when asked.
 */
@Configuration(proxyBeanMethods = false)
class TranslationBeans {
    /** The engine, shared by all windows; Spring calls its `close()` on shutdown. */
    @Bean(destroyMethod = "close")
    fun opusMtEngine(): OpusMtEngine = OpusMtEngine()

    @Bean
    fun languageDetector(): LanguageDetector = LinguaDetector()

    @Bean
    fun pairModelInstaller(engine: OpusMtEngine): ModelInstaller<PairModel> = ModelInstaller(engine)
}
